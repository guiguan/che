/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.command.manager.newmanager;

import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.JsArrayMixed;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.core.model.machine.Command;
import org.eclipse.che.api.machine.shared.dto.CommandDto;
import org.eclipse.che.api.promises.client.Function;
import org.eclipse.che.api.promises.client.FunctionException;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.promises.client.PromiseProvider;
import org.eclipse.che.api.promises.client.js.JsPromiseError;
import org.eclipse.che.api.promises.client.js.Promises;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceDto;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.command.ApplicableContext;
import org.eclipse.che.ide.api.command.CommandImpl;
import org.eclipse.che.ide.api.command.CommandManager3;
import org.eclipse.che.ide.api.command.CommandPage;
import org.eclipse.che.ide.api.command.CommandType;
import org.eclipse.che.ide.api.command.CommandTypeRegistry;
import org.eclipse.che.ide.api.command.CommandWithContext;
import org.eclipse.che.ide.api.component.WsAgentComponent;
import org.eclipse.che.ide.api.project.MutableProjectConfig;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.workspace.WorkspaceReadyEvent;
import org.eclipse.che.ide.api.workspace.WorkspaceServiceClient;
import org.eclipse.che.ide.dto.DtoFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Collections.emptyList;
import static org.eclipse.che.api.project.shared.Constants.COMMANDS_ATTRIBUTE_NAME;
import static org.eclipse.che.api.workspace.shared.Constants.COMMAND_PREVIEW_URL_ATTRIBUTE_NAME;

/**
 * Implementation of {@link CommandManager3}.
 *
 * @author Artem Zatsarynnyi
 */
@Singleton
public class CommandManagerImpl3 implements CommandManager3, WsAgentComponent, WorkspaceReadyEvent.WorkspaceReadyHandler {

    private final CommandTypeRegistry             commandTypeRegistry;
    private final AppContext                      appContext;
    private final WorkspaceServiceClient          workspaceServiceClient;
    //    private final MachineServiceClient    machineServiceClient;
    private final DtoFactory                      dtoFactory;
    private final ProjectCommandManagerDelegate   projectCommandManagerDelegate;
    private final WorkspaceCommandManagerDelegate workspaceCommandManagerDelegate;
    private final PromiseProvider                 promiseProvider;
//    private final MacroProcessor          macroProcessor;
//    private final CommandConsoleFactory   commandConsoleFactory;
//    private final ProcessesPanelPresenter processesPanelPresenter;

    private final Map<String, CommandImpl>               workspaceCommands;
    private final Map<Project, Map<String, CommandImpl>> projectCommands;
    private final Map<String, CommandWithContext>        commands;

    private final Set<CommandChangedListener> commandChangedListeners;

    @Inject
    public CommandManagerImpl3(CommandTypeRegistry commandTypeRegistry,
                               AppContext appContext,
                               WorkspaceServiceClient workspaceServiceClient,
//                               MachineServiceClient machineServiceClient,
                               DtoFactory dtoFactory,
//                               EventBus eventBus,
                               ProjectCommandManagerDelegate projectCommandManagerDelegate,
                               WorkspaceCommandManagerDelegate workspaceCommandManagerDelegate,
                               PromiseProvider promiseProvider,
                               EventBus eventBus) {
//                               MacroProcessor macroProcessor
//                               CommandConsoleFactory commandConsoleFactory,
//                               ProcessesPanelPresenter processesPanelPresenter) {
        this.commandTypeRegistry = commandTypeRegistry;
        this.appContext = appContext;
        this.workspaceServiceClient = workspaceServiceClient;
//        this.machineServiceClient = machineServiceClient;
        this.dtoFactory = dtoFactory;
        this.projectCommandManagerDelegate = projectCommandManagerDelegate;
        this.workspaceCommandManagerDelegate = workspaceCommandManagerDelegate;
        this.promiseProvider = promiseProvider;
//        this.macroProcessor = macroProcessor;
//        this.commandConsoleFactory = commandConsoleFactory;
//        this.processesPanelPresenter = processesPanelPresenter;

        workspaceCommands = new HashMap<>();
        projectCommands = new HashMap<>();
        commands = new HashMap<>();

        commandChangedListeners = new HashSet<>();

        eventBus.addHandler(WorkspaceReadyEvent.getType(), this);
    }

    private void fetchCommands() {
        workspaceCommandManagerDelegate.getCommands(appContext.getWorkspaceId()).then(new Operation<List<CommandImpl>>() {
            @Override
            public void apply(List<CommandImpl> arg) throws OperationException {
                for (CommandImpl command : arg) {
                    final CommandWithContext commandWithContext = new CommandWithContext(command);
                    final ApplicableContext applicableContext = commandWithContext.getApplicableContext();
                    applicableContext.setWorkspaceApplicable(true);

                    commands.put(command.getName(), commandWithContext);
                }

                for (Project project : appContext.getProjects()) {
                    for (CommandImpl projectCommand : projectCommandManagerDelegate.getCommands(project)) {
                        CommandWithContext commandWithContext = commands.get(projectCommand.getName());
                        if (commandWithContext == null) {
                            commandWithContext = new CommandWithContext(projectCommand);

                            commands.put(commandWithContext.getName(), commandWithContext);
                        }

                        commandWithContext.getApplicableContext().addApplicableProject(project.getPath());
                    }
                }
            }
        });
    }

    @Override
    public List<CommandWithContext> getCommands() {
        // return copy of the commands in order to prevent it modification directly
        List<CommandWithContext> list = new ArrayList<>(commands.size());
        for (CommandWithContext command : commands.values()) {
            list.add(command);
        }

        return list;
    }

    @Override
    public Promise<CommandWithContext> createCommand(final String type, final ApplicableContext applicableContext) {

        final CommandWithContext cmdWithCntx = new CommandWithContext("name", "cmd", type);

        List<Promise<CommandImpl>> commandPromises = new ArrayList<>();

        if (applicableContext.isWorkspaceApplicable()) {
            Promise<CommandImpl> p = workspaceCommandManagerDelegate.createCommand(cmdWithCntx).then(new Operation<CommandImpl>() {
                @Override
                public void apply(CommandImpl arg) throws OperationException {
                    cmdWithCntx.getApplicableContext().setWorkspaceApplicable(true);
                }
            });

            commandPromises.add(p);
        }

        if (!applicableContext.getApplicableProjects().isEmpty()) {
            for (final String projectPath : applicableContext.getApplicableProjects()) {

                final Project project = getProjectByPath(projectPath);

                Promise<CommandImpl> p =
                        projectCommandManagerDelegate.createCommand(project, cmdWithCntx).then(new Operation<CommandImpl>() {
                            @Override
                            public void apply(CommandImpl arg) throws OperationException {
                                cmdWithCntx.getApplicableContext().addApplicableProject(projectPath);
                            }
                        });

                commandPromises.add(p);
            }
        }

        Promise[] projectPromisesArray = new Promise[commandPromises.size()];
        for (Promise<CommandImpl> commandPromise : commandPromises) {
            projectPromisesArray[commandPromises.indexOf(commandPromise)] = commandPromise;
        }

        return promiseProvider.all(projectPromisesArray).then(new Function<JsArrayMixed, CommandWithContext>() {
            @Override
            public CommandWithContext apply(JsArrayMixed ignore) throws FunctionException {
                commands.put(cmdWithCntx.getName(), cmdWithCntx);

                notifyCommandAdded(cmdWithCntx);

                return cmdWithCntx;
            }
        });
    }

    private Project getProjectByPath(String path) {
        for (Project project : appContext.getProjects()) {
            if (path.equals(project.getPath())) {
                return project;
            }
        }

        return null;
    }

    @Override
    public Promise<CommandImpl> createWorkspaceCommand(String type) {
        final CommandType commandType = commandTypeRegistry.getCommandTypeById(type);

        if (commandType == null) {
            return Promises.reject(JsPromiseError.create("Unknown command type: " + type));
        }

        return createWorkspaceCommand(getUniqueCommandName(type, null),
                                      commandType.getCommandLineTemplate(),
                                      type,
                                      new HashMap<String, String>());
    }

    @Override
    public Promise<CommandImpl> createWorkspaceCommand(String desirableName,
                                                       String commandLine,
                                                       String type,
                                                       Map<String, String> attributes) {
        final CommandType commandType = commandTypeRegistry.getCommandTypeById(type);

        if (commandType == null) {
            return Promises.reject(JsPromiseError.create("Unknown command type: " + type));
        }

        attributes.put(COMMAND_PREVIEW_URL_ATTRIBUTE_NAME, commandType.getPreviewUrlTemplate());

        return createWorkspaceCommand(new CommandImpl(getUniqueCommandName(type, desirableName),
                                                      commandLine,
                                                      type,
                                                      attributes));
    }

    private Promise<CommandImpl> createWorkspaceCommand(final CommandImpl command) {
        final CommandDto commandDto = dtoFactory.createDto(CommandDto.class)
                                                .withName(command.getName())
                                                .withCommandLine(command.getCommandLine())
                                                .withType(command.getType())
                                                .withAttributes(command.getAttributes());

        return workspaceServiceClient.addCommand(appContext.getWorkspaceId(), commandDto).then(new Function<WorkspaceDto, CommandImpl>() {
            @Override
            public CommandImpl apply(WorkspaceDto arg) throws FunctionException {
                workspaceCommands.put(command.getName(), command);

                notifyCommandAdded(command);

                return command;
            }
        });
    }

    @Override
    public Promise<CommandImpl> updateWorkspaceCommand(final String commandName, final CommandImpl command) {
        final CommandDto commandDto = dtoFactory.createDto(CommandDto.class)
                                                .withName(getUniqueCommandName(command.getType(), command.getName()))
                                                .withCommandLine(command.getCommandLine())
                                                .withType(command.getType())
                                                .withAttributes(command.getAttributes());

        return workspaceServiceClient.updateCommand(appContext.getWorkspaceId(), commandName, commandDto)
                                     .then(new Function<WorkspaceDto, CommandImpl>() {
                                         @Override
                                         public CommandImpl apply(WorkspaceDto arg) throws FunctionException {
                                             final CommandImpl updatedCommand = new CommandImpl(command);
                                             updatedCommand.setName(commandDto.getName());

                                             workspaceCommands.remove(commandName);
                                             workspaceCommands.put(updatedCommand.getName(), updatedCommand);

                                             notifyCommandUpdated(updatedCommand);

                                             return updatedCommand;
                                         }
                                     });
    }

    @Override
    public Promise<Void> removeCommand(final String commandName) {

        final CommandWithContext cmdWithCntx = commands.get(commandName);
        final ApplicableContext applicableContext = cmdWithCntx.getApplicableContext();

        List<Promise<Void>> commandPromises = new ArrayList<>();

        if (applicableContext.isWorkspaceApplicable()) {
            Promise<Void> p = workspaceCommandManagerDelegate.removeCommand(commandName).then(new Operation<Void>() {
                @Override
                public void apply(Void arg) throws OperationException {
//                    cmdWithCntx.getApplicableContext().setWorkspaceApplicable(true);
                }
            });

            commandPromises.add(p);
        }

        if (!applicableContext.getApplicableProjects().isEmpty()) {
            for (final String projectPath : applicableContext.getApplicableProjects()) {

                final Project project = getProjectByPath(projectPath);

                Promise<Void> p = projectCommandManagerDelegate.removeCommand(project, commandName).then(new Operation<Void>() {
                    @Override
                    public void apply(Void arg) throws OperationException {
//                                cmdWithCntx.getApplicableContext().addApplicableProject(projectPath);
                    }
                });

                commandPromises.add(p);
            }
        }

        Promise[] projectPromisesArray = new Promise[commandPromises.size()];
        for (Promise<Void> commandPromise : commandPromises) {
            projectPromisesArray[commandPromises.indexOf(commandPromise)] = commandPromise;
        }

        return promiseProvider.all(projectPromisesArray).then(new Function<JsArrayMixed, Void>() {
            @Override
            public Void apply(JsArrayMixed ignore) throws FunctionException {
                commands.remove(cmdWithCntx.getName());

                notifyCommandRemoved(cmdWithCntx);

                return null;
            }
        });
    }

    @Override
    public List<CommandImpl> getProjectCommands(Project project) {
        List<String> attributeValues = project.getAttributes(COMMANDS_ATTRIBUTE_NAME);
        if (attributeValues == null) {
            return emptyList();
        }

        Map<String, CommandImpl> commands = new HashMap<>(attributeValues.size());

        for (String commandJson : attributeValues) {
            Command command = dtoFactory.createDtoFromJson(commandJson, CommandDto.class);

            commands.put(command.getName(), new CommandImpl(command));
        }

        // TODO: rework it. Need to read all projects's commands on manager start-up
        projectCommands.put(project, commands);

        return new ArrayList<>(commands.values());
    }

    @Override
    public Promise<CommandImpl> createProjectCommand(Project project, String type) {
        final CommandType commandType = commandTypeRegistry.getCommandTypeById(type);

        if (commandType == null) {
            return Promises.reject(JsPromiseError.create("Unknown command type: " + type));
        }

        return createProjectCommand(project,
                                    getUniqueCommandName(type, null),
                                    commandType.getCommandLineTemplate(),
                                    type,
                                    new HashMap<String, String>());
    }

    @Override
    public Promise<CommandImpl> createProjectCommand(Project project,
                                                     String desirableName,
                                                     String commandLine,
                                                     String type,
                                                     Map<String, String> attributes) {
        final CommandType commandType = commandTypeRegistry.getCommandTypeById(type);

        if (commandType == null) {
            return Promises.reject(JsPromiseError.create("Unknown command type: " + type));
        }

        attributes.put(COMMAND_PREVIEW_URL_ATTRIBUTE_NAME, commandType.getPreviewUrlTemplate());

        return createProjectCommand(project, new CommandImpl(getUniqueCommandName(type, desirableName),
                                                             commandLine,
                                                             type,
                                                             attributes));
    }

    private Promise<CommandImpl> createProjectCommand(Project project, final CommandImpl command) {
        final Map<String, CommandImpl> commands;

        if (projectCommands.containsKey(project)) {
            commands = projectCommands.get(project);
        } else {
            commands = new HashMap<>();
        }

        if (commands.get(command.getName()) != null) {
            return Promises.reject(JsPromiseError.create("Command with name " + command.getName() +
                                                         " is already associated to the project " + project.getName()));
        }

        List<CommandImpl> commandsToUpdate = new ArrayList<>(commands.values());
        commandsToUpdate.add(command);

        return updateProject(project, commandsToUpdate).then(new Function<Void, CommandImpl>() {
            @Override
            public CommandImpl apply(Void arg) throws FunctionException {
                commands.put(command.getName(), command);

                notifyCommandAdded(command);

                return command;
            }
        });
    }

    @Override
    public Promise<CommandImpl> updateProjectCommand(Project project, String commandName, CommandImpl command) {
        final Map<String, CommandImpl> commands = projectCommands.get(project);

        if (commands == null || !commands.containsKey(commandName)) {
            return Promises.reject(JsPromiseError.create("Command " + commandName + " is not associated with the project " +
                                                         project.getName()));
        }

        commands.remove(commandName);

        final CommandImpl updatedCommand = new CommandImpl(command);
        updatedCommand.setName(getUniqueCommandName(command.getType(), command.getName()));

        List<CommandImpl> commandsToUpdate = new ArrayList<>(commands.values());
        commandsToUpdate.add(updatedCommand);

        return updateProject(project, commandsToUpdate).then(new Function<Void, CommandImpl>() {
            @Override
            public CommandImpl apply(Void arg) throws FunctionException {
                commands.put(updatedCommand.getName(), updatedCommand);

                notifyCommandUpdated(updatedCommand);

                return updatedCommand;
            }
        });
    }

    @Override
    public Promise<Void> removeProjectCommand(Project project, final String name) {
        final Map<String, CommandImpl> commands = projectCommands.get(project);

        if (commands == null || !commands.containsKey(name)) {
            return Promises.reject(JsPromiseError.create("Command " + name + " isn't associated with the project " + project.getName()));
        }

        Map<String, CommandImpl> commandsToUpdate = new HashMap<>(commands);
        commandsToUpdate.remove(name);

        return updateProject(project, new ArrayList<>(commandsToUpdate.values())).then(new Operation<Void>() {
            @Override
            public void apply(Void arg) throws OperationException {
                notifyCommandRemoved(commands.remove(name));
            }
        });
    }

    private Promise<Void> updateProject(Project project, List<CommandImpl> commands) {
        MutableProjectConfig config = new MutableProjectConfig(project);
        Map<String, List<String>> attributes = config.getAttributes();

        List<String> commandsList = new ArrayList<>(attributes.size());
        for (CommandImpl command : commands) {
            CommandDto commandDto = dtoFactory.createDto(CommandDto.class)
                                              .withName(command.getName())
                                              .withType(command.getType())
                                              .withCommandLine(command.getCommandLine())
                                              .withAttributes(command.getAttributes());
            commandsList.add(dtoFactory.toJson(commandDto));
        }

        attributes.put(COMMANDS_ATTRIBUTE_NAME, commandsList);

        return project.update().withBody(config).send().then(new Function<Project, Void>() {
            @Override
            public Void apply(Project arg) throws FunctionException {
                return null;
            }
        });
    }

    @Override
    public List<CommandPage> getPages(String type) {
        CommandType commandType = commandTypeRegistry.getCommandTypeById(type);
        return commandType != null ? commandType.getPages() : Collections.<CommandPage>emptyList();
    }

//    @Override
//    public void executeCommand(final CommandImpl command, final Machine machine) {
//        final String outputChannel = "process:output:" + UUID.uuid();
//
//        final CommandOutputConsole console = commandConsoleFactory.create(command, machine);
//        console.listenToOutput(outputChannel);
//        processesPanelPresenter.addCommandOutput(machine.getId(), console);
//
//        macroProcessor.expandMacros(command.getCommandLine()).then(new Operation<String>() {
//            @Override
//            public void apply(String arg) throws OperationException {
//                final CommandImpl toExecute = new CommandImpl(command);
//                toExecute.setCommandLine(arg);
//
//                // if command line has not specified the shell attribute, use bash to be backward compliant for user commands
//                Map<String, String> attributes = toExecute.getAttributes();
//                if (attributes == null) {
//                    attributes = new HashMap<>(1);
//                    attributes.put("shell", "/bin/bash");
//                    toExecute.setAttributes(attributes);
//                } else if (!attributes.containsKey("shell")) {
//                    attributes = new HashMap<>(attributes.size() + 1);
//                    attributes.put("shell", "/bin/bash");
//                    attributes.putAll(toExecute.getAttributes());
//                    toExecute.setAttributes(attributes);
//                }
//
//                Log.info(CommandManagerImpl3.class,
//                         "Using shell " + toExecute.getAttributes().get("shell") + " for invoking command '" + command.getName() + "'");
//
//                Promise<MachineProcessDto> processPromise = machineServiceClient.executeCommand(machine.getWorkspaceId(),
//                                                                                                machine.getId(),
//                                                                                                toExecute,
//                                                                                                outputChannel);
//                processPromise.then(new Operation<MachineProcessDto>() {
//                    @Override
//                    public void apply(MachineProcessDto process) throws OperationException {
//                        console.attachToProcess(process);
//                    }
//                });
//            }
//        });
//    }

    @Override
    public void addCommandChangedListener(CommandChangedListener listener) {
        commandChangedListeners.add(listener);
    }

    @Override
    public void removeCommandChangedListener(CommandChangedListener listener) {
        commandChangedListeners.remove(listener);
    }

    private void notifyCommandAdded(CommandImpl command) {
        for (CommandChangedListener listener : commandChangedListeners) {
            listener.onCommandAdded(command);
        }
    }

    private void notifyCommandRemoved(CommandImpl command) {
        for (CommandChangedListener listener : commandChangedListeners) {
            listener.onCommandRemoved(command);
        }
    }

    private void notifyCommandUpdated(CommandImpl command) {
        for (CommandChangedListener listener : commandChangedListeners) {
            listener.onCommandUpdated(command);
        }
    }

    // TODO: need to create different algorithm for checking project commands uniqueness
    // they have to be unique within the project

    /**
     * Returns {@code customName} if it's unique within the given {@code customType}
     * or newly generated name if it isn't unique within the given {@code customType}.
     */
    private String getUniqueCommandName(String customType, String customName) {
        final CommandType commandType = commandTypeRegistry.getCommandTypeById(customType);
        final Set<String> commandNames = workspaceCommands.keySet();

        final String newCommandName;

        if (isNullOrEmpty(customName)) {
            newCommandName = "new" + commandType.getDisplayName();
        } else {
            if (!commandNames.contains(customName)) {
                return customName;
            }
            newCommandName = customName + " copy";
        }

        if (!commandNames.contains(newCommandName)) {
            return newCommandName;
        }

        for (int count = 1; count < 1000; count++) {
            if (!commandNames.contains(newCommandName + "-" + count)) {
                return newCommandName + "-" + count;
            }
        }

        return newCommandName;
    }

    // TODO: just for eager instantiating
    @Override
    public void start(Callback<WsAgentComponent, Exception> callback) {
        callback.onSuccess(this);
    }

    @Override
    public void onWorkspaceReady(WorkspaceReadyEvent event) {
        fetchCommands();
    }
}