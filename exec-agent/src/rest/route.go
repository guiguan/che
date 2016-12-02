package rest

import (
	"fmt"
	"net/http"
	"strings"
)

const (
	maxNameLen   = 40
	maxMethodLen = len("DELETE")
)

// Handler for http routes
// vars variable contain only path parameters if any specified for given route
type HttpRouteHandlerFunc func(w http.ResponseWriter, r *http.Request, params Params) error

type Params interface {
	Get(name string) string
}

// Describes route for http requests
type Route struct {

	// Http method e.g. 'GET'
	Method string

	// The name of the http route, used in logs
	// this name is unique for all the application http routes
	// example: 'StartProcess'
	Name string

	// The path of the http route which this route is mapped to
	// example: '/process'
	Path string

	// The function used for handling http request
	HandleFunc HttpRouteHandlerFunc
}

// Named group of http routes, those groups
// should be defined by separate apis, and then combined together
type RoutesGroup struct {

	// The name of this group e.g.: 'ProcessRoutes'
	Name string

	// The http routes of this group
	Items []Route
}

func (r *Route) String() string {
	name := r.Name + " " + strings.Repeat(".", maxNameLen-len(r.Name))
	method := r.Method + strings.Repeat(" ", maxMethodLen-len(r.Method))
	return fmt.Sprintf("%s %s %s", name, method, r.Path)
}

func WriteError(w http.ResponseWriter, err error) {
	if apiErr, ok := err.(ApiError); ok {
		http.Error(w, apiErr.Error(), apiErr.Code)
	} else {
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}
}
