package auth

import (
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/eclipse/che/exec-agent/rest"
)

const (
	DefaultTokensExpirationTimeoutInMinutes = 10
)

// Authenticates all the http calls on workspace master
// checking if provided by request token is valid, if authentication is successful
// then calls ServerHTTP on delegate, otherwise if UnauthorizedHandler is configured
// then uses it to handle the request if not then returns 401 with appropriate error message
type Handler struct {
	Delegate            http.Handler
	ApiEndpoint         string
	Cache               *TokenCache
	UnauthorizedHandler func(w http.ResponseWriter, req *http.Request)
}

func (handler Handler) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	token := req.URL.Query().Get("token")
	if handler.Cache != nil && handler.Cache.Contains(token) {
		handler.Delegate.ServeHTTP(w, req)
	} else if err := authenticateOnMaster(handler.ApiEndpoint, token); err == nil {
		if handler.Cache != nil {
			handler.Cache.Put(token)
		}
		handler.Delegate.ServeHTTP(w, req)
	} else if handler.UnauthorizedHandler != nil {
		handler.UnauthorizedHandler(w, req)
	} else {
		http.Error(w, err.Error(), http.StatusUnauthorized)
	}
}

// Authentication tokens cache.
type TokenCache struct {
	sync.RWMutex
	tokens        map[string]time.Time
	ticker        *time.Ticker
	expireTimeout time.Duration
}

func NewCache(expireDuration time.Duration, period time.Duration) *TokenCache {
	cache := &TokenCache{
		tokens:        make(map[string]time.Time),
		expireTimeout: expireDuration,
	}
	go cache.expirePeriodicaly(period)
	return cache
}

// Puts token into the cache.
func (cache *TokenCache) Put(token string) {
	cache.Lock()
	defer cache.Unlock()
	cache.tokens[token] = time.Now().Add(cache.expireTimeout)
}

// Removes the token from the cache.
func (cache *TokenCache) Expire(token string) {
	cache.Lock()
	defer cache.Unlock()
	delete(cache.tokens, token)
}

// Returns true if token is present in the cache and false otherwise.
func (cache *TokenCache) Contains(token string) bool {
	cache.RLock()
	defer cache.RUnlock()
	_, ok := cache.tokens[token]
	return ok
}

func (cache *TokenCache) expirePeriodicaly(period time.Duration) {
	cache.ticker = time.NewTicker(period)
	for range cache.ticker.C {
		now := time.Now()
		cache.Lock()
		for token, expTime := range cache.tokens {
			if expTime.Before(now) {
				delete(cache.tokens, token)
			}
		}
		cache.Unlock()
	}
}

func authenticateOnMaster(apiEndpoint string, tokenParam string) error {
	if tokenParam == "" {
		return rest.Unauthorized(errors.New("Authentication failed: missing 'token' query parameter"))
	}
	req, err := http.NewRequest("GET", apiEndpoint+"/machine/token/user/"+tokenParam, nil)
	if err != nil {
		return rest.Unauthorized(err)
	}
	req.Header.Add("Authorization", tokenParam)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return rest.Unauthorized(err)
	}
	if resp.StatusCode != 200 {
		return rest.Unauthorized(errors.New(fmt.Sprintf("Authentication failed, token: %s is invalid", tokenParam)))
	}
	return nil
}
