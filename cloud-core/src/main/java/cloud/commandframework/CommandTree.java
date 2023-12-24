//
// MIT License
//
// Copyright (c) 2022 Alexander Söderberg & Contributors
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package cloud.commandframework;

import cloud.commandframework.arguments.DefaultValue;
import cloud.commandframework.arguments.LiteralParser;
import cloud.commandframework.arguments.aggregate.AggregateCommandParser;
import cloud.commandframework.arguments.flags.CommandFlagParser;
import cloud.commandframework.arguments.parser.ArgumentParseResult;
import cloud.commandframework.arguments.suggestion.Suggestion;
import cloud.commandframework.context.CommandContext;
import cloud.commandframework.context.CommandInput;
import cloud.commandframework.context.ParsingContext;
import cloud.commandframework.exceptions.AmbiguousNodeException;
import cloud.commandframework.exceptions.ArgumentParseException;
import cloud.commandframework.exceptions.InvalidCommandSenderException;
import cloud.commandframework.exceptions.InvalidSyntaxException;
import cloud.commandframework.exceptions.NoCommandInLeafException;
import cloud.commandframework.exceptions.NoPermissionException;
import cloud.commandframework.exceptions.NoSuchCommandException;
import cloud.commandframework.internal.CommandInputTokenizer;
import cloud.commandframework.internal.CommandNode;
import cloud.commandframework.internal.SuggestionContext;
import cloud.commandframework.permission.Permission;
import cloud.commandframework.setting.ManagerSetting;
import cloud.commandframework.util.CompletableFutures;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Tree containing all commands and command paths.
 * <p>
 * All {@link Command commands} consists of unique paths made out of {@link CommandComponent components}.
 * These arguments may be literals or variables. Command may either be required
 * or optional, with the requirement that no optional argument precedes a required argument.
 * <p>
 * The {@link Command commands} are stored in this tree and the nodes of tree consists of the command
 * {@link CommandComponent components}. Each leaf node of the tree should contain a fully parsed
 * {@link Command}. It is thus possible to walk the tree and determine whether the supplied
 * input from a command sender constitutes a proper command.
 * <p>
 * When parsing input, the tree will be walked until one of four scenarios occur:
 * <ol>
 *     <li>The input queue is empty at a non-leaf node</li>
 *     <li>The input queue is not empty following a leaf node</li>
 *     <li>No child node is able to accept the input</li>
 *     <li>The input queue is empty following a leaf node</li>
 * </ol>
 * <p>
 * Scenarios one and two would result in a {@link InvalidSyntaxException} being thrown, whereas
 * scenario three would result in a {@link NoSuchCommandException} if occurring at the root node
 * or a {@link InvalidSyntaxException} otherwise. Only the fourth scenario would result in a complete
 * command being parsed.
 *
 * @param <C> Command sender type
 */
@API(status = API.Status.INTERNAL, consumers = "cloud.commandframework.*")
public final class CommandTree<C> {

    private final Object commandLock = new Object();

    private final CommandNode<C> internalTree = new CommandNode<>(null);
    private final CommandManager<C> commandManager;

    private CommandTree(final @NonNull CommandManager<C> commandManager) {
        this.commandManager = commandManager;
    }

    /**
     * Creates a new command tree instance
     *
     * @param commandManager Command manager
     * @param <C>            Command sender type
     * @return the created command tree
     */
    public static <C> @NonNull CommandTree<C> newTree(final @NonNull CommandManager<C> commandManager) {
        return new CommandTree<>(commandManager);
    }

    /**
     * Returns the command manager that was used to create this command tree
     *
     * @return Command manager
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull CommandManager<C> commandManager() {
        return this.commandManager;
    }

    /**
     * Returns an immutable view containing of the root nodes of the command tree
     *
     * @return immutable view of the root nodes
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull Collection<@NonNull CommandNode<C>> rootNodes() {
        return this.internalTree.children();
    }

    /**
     * Returns a named root node, if it exists
     *
     * @param name root node name
     * @return the found root node, or {@code null}
     */
    public @Nullable CommandNode<C> getNamedNode(final @Nullable String name) {
        for (final CommandNode<C> node : this.rootNodes()) {
            final CommandComponent<C> component = node.component();
            if (component == null || !(component.type() == CommandComponent.ComponentType.LITERAL)) {
                continue;
            }
            for (final String alias : component.aliases()) {
                if (alias.equalsIgnoreCase(name)) {
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * Attempts to parse string input into a command
     *
     * @param commandContext Command context instance
     * @param commandInput   Input
     * @return parsed command, if one could be found
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull CompletableFuture<@Nullable Command<C>> parse(
            final @NonNull CommandContext<C> commandContext,
            final @NonNull CommandInput commandInput
    ) {
        // Special case for empty command trees.
        if (this.internalTree.isLeaf() && this.internalTree.component() == null) {
            return CompletableFutures.failedFuture(
                    new NoSuchCommandException(
                            commandContext.sender(),
                            new ArrayList<>(),
                            commandInput.peekString()
                    )
            );
        }

       return this.parseCommand(
                new ArrayList<>(),
                commandContext,
                commandInput,
                this.internalTree
        ).thenCompose(command -> {
            if (command != null
                    && command.senderType().isPresent()
                    && !command.senderType().get().isInstance(commandContext.sender())) {
                return CompletableFutures.failedFuture(
                        new InvalidCommandSenderException(
                                commandContext.sender(),
                                command.senderType().get(),
                                new ArrayList<>(command.components()),
                                command
                        )
                );
            }
            return CompletableFuture.completedFuture(command);
        });
    }

    private @NonNull CompletableFuture<@Nullable Command<C>> parseCommand(
            final @NonNull List<@NonNull CommandComponent<C>> parsedArguments,
            final @NonNull CommandContext<C> commandContext,
            final @NonNull CommandInput commandInput,
            final @NonNull CommandNode<C> root
    ) {
        final Permission permission = this.findMissingPermission(commandContext.sender(), root);
        if (permission != null) {
            return CompletableFutures.failedFuture(
                    new NoPermissionException(
                            permission,
                            commandContext.sender(),
                            this.getChain(root)
                                    .stream()
                                    .filter(node -> node.component() != null)
                                    .map(CommandNode::component)
                                    .collect(Collectors.toList())
                    )
            );
        }

        final CompletableFuture<@Nullable Command<C>> parsedChild = this.attemptParseUnambiguousChild(
                parsedArguments,
                commandContext,
                root,
                commandInput
        );
        if (parsedChild != null) {
            return parsedChild;
        }

        // There are 0 or more static arguments as children. No variable child arguments are present
        if (root.children().isEmpty()) {
            final CommandComponent<C> rootComponent = root.component();
            if (rootComponent == null || rootComponent.owningCommand() == null || !commandInput.isEmpty()) {
                // Too many arguments. We have a unique path, so we can send the entire context
                return CompletableFutures.failedFuture(
                        new InvalidSyntaxException(
                                this.commandManager.commandSyntaxFormatter()
                                        .apply(parsedArguments, root),
                                commandContext.sender(), this.getChain(root)
                                .stream()
                                .filter(node -> node.component() != null)
                                .map(CommandNode::component)
                                .collect(Collectors.toList())
                        )
                );
            }
            return CompletableFuture.completedFuture(rootComponent.owningCommand());
        }

        CompletableFuture<Command<C>> childCompletable = CompletableFuture.completedFuture(null);
        for (final CommandNode<C> child : new ArrayList<>(root.children())) {
            if (child.component() == null) {
                continue;
            }

            childCompletable = childCompletable.thenCompose(previousResult -> {
                if (previousResult != null) {
                    return CompletableFuture.completedFuture(previousResult);
                }

                final CommandComponent<C> component = Objects.requireNonNull(child.component());
                final ParsingContext<C> parsingContext = commandContext.createParsingContext(component);

                // Copy the current queue so that we can deduce the captured input.
                final CommandInput currentInput = commandInput.copy();

                parsingContext.markStart();
                commandContext.currentComponent(component);

                return component.parser()
                        .parseFuture(commandContext, commandInput)
                        .thenCompose(result -> {
                            parsingContext.markEnd();
                            parsingContext.success(!result.failure().isPresent());

                            final List<String> consumedTokens = tokenize(currentInput);
                            consumedTokens.removeAll(tokenize(commandInput));
                            parsingContext.consumedInput(consumedTokens);

                            if (result.parsedValue().isPresent()) {
                                parsedArguments.add(component);
                                return this.parseCommand(parsedArguments, commandContext, commandInput, child);
                            } else if (result.failure().isPresent()) {
                                commandInput.cursor(currentInput.cursor());
                            }
                            // We do not want to respond with a parsing error, as parsing errors are meant to propagate.
                            // Just not being able to parse is not enough.
                            return CompletableFuture.completedFuture(null);
                        });
            });
        }

        return childCompletable.thenCompose(completedCommand -> {
                    if (completedCommand != null) {
                        return CompletableFuture.completedFuture(completedCommand);
                    }

                    // We could not find a match
                    if (root.equals(this.internalTree)) {
                       return CompletableFutures.failedFuture(
                           new NoSuchCommandException(
                                   commandContext.sender(),
                                   this.getChain(root).stream().map(CommandNode::component).collect(Collectors.toList()),
                                   commandInput.peekString()
                           )
                       );
                   }

                    // If we couldn't match a child, check if there's a command attached and execute it
                    final CommandComponent<C> rootComponent = root.component();
                    if (rootComponent != null && rootComponent.owningCommand() != null && commandInput.isEmpty()) {
                        final Command<C> command = rootComponent.owningCommand();
                        if (!this.commandManager().hasPermission(
                                commandContext.sender(),
                                command.commandPermission()
                        )) {
                            return CompletableFutures.failedFuture(
                                    new NoPermissionException(
                                            command.commandPermission(),
                                            commandContext.sender(),
                                            this.getChain(root)
                                                    .stream()
                                                    .filter(node -> node.component() != null)
                                                    .map(CommandNode::component)
                                                    .collect(Collectors.toList())
                                    )
                            );
                        }
                        return CompletableFuture.completedFuture(rootComponent.owningCommand());
                    }

                    // We know that there's no command, and we also cannot match any of the children
                    return CompletableFutures.failedFuture(
                            new InvalidSyntaxException(
                                    this.commandManager.commandSyntaxFormatter()
                                            .apply(parsedArguments, root),
                                    commandContext.sender(), this.getChain(root)
                                    .stream()
                                    .filter(node -> node.component() != null)
                                    .map(CommandNode::component)
                                    .collect(Collectors.toList())
                            )
                    );
                });
    }

    private @Nullable CompletableFuture<@Nullable Command<C>> attemptParseUnambiguousChild(
            final @NonNull List<@NonNull CommandComponent<C>> parsedArguments,
            final @NonNull CommandContext<C> commandContext,
            final @NonNull CommandNode<C> root,
            final @NonNull CommandInput commandInput
    ) {
        final List<CommandNode<C>> children = root.children();

        // Check whether it matches any of the static arguments If so, do not attempt parsing as a dynamic argument
        if (!commandInput.isEmpty() && this.matchesLiteral(children, commandInput.peekString())) {
            return null;
        }

        // If it does not match a literal, try to find the one argument node, if it exists
        // The ambiguity check guarantees that only one will be present
        final List<CommandNode<C>> argumentNodes = children.stream()
                .filter(n -> (n.component() != null && n.component().type() != CommandComponent.ComponentType.LITERAL))
                .collect(Collectors.toList());
        if (argumentNodes.size() > 1) {
            throw new IllegalStateException("Unexpected ambiguity detected, number of dynamic child nodes should not exceed 1");
        } else if (argumentNodes.isEmpty()) {
            return null;
        }
        final CommandNode<C> child = argumentNodes.get(0);

        // Check if we're allowed to execute the child command. If not, exit
        final Permission permission = this.findMissingPermission(commandContext.sender(), child);
        if (!commandInput.isEmpty() && permission != null) {
            return CompletableFutures.failedFuture(
                    new NoPermissionException(
                            permission,
                            commandContext.sender(),
                            this.getChain(child)
                                    .stream()
                                    .filter(node -> node.component() != null)
                                    .map(CommandNode::component)
                                    .collect(Collectors.toList())
                    )
            );
        }

        // If the child has no argument it cannot be executed, so we exit
        if (child.component() == null) {
            return null;
        }

        // This stores the argument value for this argument.
        Object argumentValue = null;

        // Flag arguments need to be skipped over, so that further defaults are handled
        if (commandInput.isEmpty() && !(child.component().type() == CommandComponent.ComponentType.FLAG)) {
            final CommandComponent<C> childComponent = Objects.requireNonNull(child.component());
            if (childComponent.hasDefaultValue()) {
                final DefaultValue<C, ?> defaultValue = Objects.requireNonNull(childComponent.defaultValue(), "defaultValue");

                if (defaultValue instanceof DefaultValue.ParsedDefaultValue) {
                    return this.attemptParseUnambiguousChild(
                            parsedArguments,
                            commandContext,
                            root,
                            commandInput.appendString(((DefaultValue.ParsedDefaultValue<C, ?>) defaultValue).value())
                    );
                } else {
                    argumentValue = defaultValue.evaluateDefault(commandContext);
                }
            } else if (!child.component().required()) {
                if (childComponent.owningCommand() == null) {
                    // If there are multiple children with different owning commands then it's ambiguous and
                    // not allowed, therefore we're able to pick any child command, as long as we can find it
                    CommandNode<C> node = child;
                    while (!node.isLeaf()) {
                        node = node.children().get(0);
                        final CommandComponent<C> nodeComponent = node.component();
                        if (nodeComponent != null && nodeComponent.owningCommand() != null) {
                            childComponent.owningCommand(nodeComponent.owningCommand());
                        }
                    }
                }
                return CompletableFuture.completedFuture(childComponent.owningCommand());
            } else if (child.isLeaf()) {
                final CommandComponent<C> rootComponent = root.component();
                if (rootComponent == null || rootComponent.owningCommand() == null) {
                    final List<CommandComponent<C>> components = Objects.requireNonNull(
                            childComponent.owningCommand()
                    ).components();
                    return CompletableFutures.failedFuture(
                            new InvalidSyntaxException(
                                    this.commandManager.commandSyntaxFormatter().apply(components, child),
                                    commandContext.sender(),
                                    this.getChain(root)
                                            .stream()
                                            .filter(node -> node.component() != null)
                                            .map(CommandNode::component)
                                            .collect(Collectors.toList())
                            )
                    );
                }

                final Command<C> command = rootComponent.owningCommand();
                if (this.commandManager().hasPermission(commandContext.sender(), command.commandPermission())) {
                    return CompletableFuture.completedFuture(command);
                }
                return CompletableFutures.failedFuture(
                        new NoPermissionException(
                                command.commandPermission(),
                                commandContext.sender(),
                                this.getChain(root)
                                        .stream()
                                        .filter(node -> node.component() != null)
                                        .map(CommandNode::component)
                                        .collect(Collectors.toList())
                        )
                );
            } else {
                // The child is not a leaf, but may have an intermediary executor, attempt to use it
                final CommandComponent<C> rootComponent = root.component();
                if (rootComponent == null || rootComponent.owningCommand() == null) {
                    // Child does not have a command, and so we cannot proceed
                    return CompletableFutures.failedFuture(
                            new InvalidSyntaxException(
                                    this.commandManager.commandSyntaxFormatter()
                                            .apply(parsedArguments, root),
                                    commandContext.sender(),
                                    this.getChain(root)
                                            .stream()
                                            .filter(node -> node.component() != null)
                                            .map(CommandNode::component)
                                            .collect(Collectors.toList())
                            )
                    );
                }

                // If the sender has permission to use the command, then we're completely done
                final Command<C> command = Objects.requireNonNull(rootComponent.owningCommand());
                if (this.commandManager().hasPermission(commandContext.sender(), command.commandPermission())) {
                    return CompletableFuture.completedFuture(command);
                }

                return CompletableFutures.failedFuture(
                        new NoPermissionException(
                                command.commandPermission(),
                                commandContext.sender(),
                                this.getChain(root)
                                        .stream()
                                        .filter(node -> node.component() != null)
                                        .map(CommandNode::component)
                                        .collect(Collectors.toList())
                        )
                );
            }
        }

        final CommandComponent<C> component = Objects.requireNonNull(child.component());

        final CompletableFuture<?> parseResult;
        if (argumentValue != null) {
            parseResult = CompletableFuture.completedFuture(argumentValue);
        } else {
            parseResult =
                    this.parseArgument(commandContext, child, commandInput)
                            .thenApply(ArgumentParseResult::parsedValue)
                            .thenApply(optional -> optional.orElse(null));
        }

        return parseResult.thenCompose(value -> {
           if (value == null) {
               return CompletableFuture.completedFuture(null);
           }

           commandContext.store(component.name(), value);
           if (child.isLeaf()) {
               if (commandInput.isEmpty()) {
                   return CompletableFuture.completedFuture(component.owningCommand());
               }
               return CompletableFutures.failedFuture(
                       new InvalidSyntaxException(
                               this.commandManager.commandSyntaxFormatter().apply(parsedArguments, child),
                               commandContext.sender(),
                               this.getChain(root)
                                       .stream()
                                       .filter(node -> node.component() != null)
                                       .map(CommandNode::component)
                                       .collect(Collectors.toList()
                                       )
                       )
               );
           }

            parsedArguments.add(Objects.requireNonNull(child.component()));
            return this.parseCommand(parsedArguments, commandContext, commandInput, child);
        });
    }

    private boolean matchesLiteral(final @NonNull List<@NonNull CommandNode<C>> children, final @NonNull String input) {
        return children.stream()
                .map(CommandNode::component)
                .filter(Objects::nonNull)
                .filter(n -> n.type() == CommandComponent.ComponentType.LITERAL)
                .flatMap(arg -> Stream.concat(Stream.of(arg.name()), arg.aliases().stream()))
                .anyMatch(arg -> arg.equals(input));
    }

    private @NonNull CompletableFuture<ArgumentParseResult<?>> parseArgument(
            final @NonNull CommandContext<C> commandContext,
            final @NonNull CommandNode<C> node,
            final @NonNull CommandInput commandInput
    ) {
        final ParsingContext<C> parsingContext = commandContext.createParsingContext(node.component());
        parsingContext.markStart();

        final ArgumentParseResult<Boolean> preParseResult = node.component().preprocess(commandContext, commandInput);

        if (preParseResult.failure().isPresent() || !preParseResult.parsedValue().orElse(false)) {
            parsingContext.markEnd();
            parsingContext.success(false);
            return CompletableFuture.completedFuture(preParseResult);
        }

        commandContext.currentComponent(node.component());

        // Copy the current queue so that we can deduce the captured input.
        final CommandInput currentInput = commandInput.copy();

        return node.component().parser()
                .parseFuture(commandContext, commandInput)
                .thenCompose(result -> {
                    // We remove all remaining queue, and then we'll have a list of the captured input.
                    final List<String> consumedInput = tokenize(currentInput);
                    consumedInput.removeAll(tokenize(commandInput));
                    parsingContext.consumedInput(consumedInput);

                    parsingContext.markEnd();
                    parsingContext.success(false);

                    final CompletableFuture<ArgumentParseResult<?>> resultFuture = new CompletableFuture<>();

                    if (result.failure().isPresent()) {
                        commandInput.cursor(currentInput.cursor());
                        resultFuture.completeExceptionally(
                                new ArgumentParseException(
                                        result.failure().get(),
                                        commandContext.sender(),
                                        this.getChain(node)
                                                .stream()
                                                .filter(n -> n.component() != null)
                                                .map(CommandNode::component)
                                                .collect(Collectors.toList())
                                )
                        );
                    } else {
                        resultFuture.complete(result);
                    }
                    return resultFuture;
                });
    }

    /**
     * Returns suggestions from the input queue
     *
     * @param context      Context instance
     * @param commandInput Input
     * @return String suggestions. These should be filtered based on {@link String#startsWith(String)}
     * @since 2.0.0
     */
    @API(status = API.Status.STABLE, since = "2.0.0")
    public @NonNull CompletableFuture<List<@NonNull Suggestion>> getSuggestions(
            final @NonNull CommandContext<C> context,
            final @NonNull CommandInput commandInput
    ) {
        final SuggestionContext<C> suggestionContext = new SuggestionContext<>(
                this.commandManager.commandSuggestionProcessor(),
                context,
                commandInput
        );
        return this.getSuggestions(suggestionContext, commandInput, this.internalTree).thenApply(SuggestionContext::suggestions);
    }

    @SuppressWarnings("MixedMutabilityReturnType")
    private @NonNull CompletableFuture<SuggestionContext<C>> getSuggestions(
            final @NonNull SuggestionContext<C> context,
            final @NonNull CommandInput commandInput,
            final @NonNull CommandNode<C> root
    ) {
        // If the sender isn't allowed to access the root node, no suggestions are needed
        if (this.findMissingPermission(context.commandContext().sender(), root) != null) {
            return CompletableFuture.completedFuture(context);
        }

        final List<CommandNode<C>> children = root.children();
        final List<CommandNode<C>> staticArguments = children.stream()
                .filter(n -> n.component() != null)
                .filter(n -> n.component().type() == CommandComponent.ComponentType.LITERAL)
                .collect(Collectors.toList());

        // Try to see if any of the static literals can be parsed (matches exactly)
        // If so, enter that node of the command tree for deeper suggestions
        if (!staticArguments.isEmpty() && !commandInput.isEmpty(true /* ignoringWhitespace */)) {
            final CommandInput commandInputCopy = commandInput.copy();
            for (CommandNode<C> child : staticArguments) {
                final CommandComponent<C> childComponent = child.component();
                if (childComponent == null) {
                    continue;
                }

                context.commandContext().currentComponent(childComponent);
                final ArgumentParseResult<?> result = childComponent.parser().parse(
                        context.commandContext(),
                        commandInput
                );

                if (result.failure().isPresent()) {
                    commandInput.cursor(commandInputCopy.cursor());
                }

                if (!result.parsedValue().isPresent()) {
                    continue;
                }

                if (commandInput.isEmpty()) {
                    // We've already matched one exactly, no use looking further
                    break;
                }

                return this.getSuggestions(context, commandInput, child);
            }

            // Restore original queue
            commandInput.cursor(commandInputCopy.cursor());
        }

        // Calculate suggestions for the literal arguments
        CompletableFuture<SuggestionContext<C>> suggestionFuture = CompletableFuture.completedFuture(context);
        if (commandInput.remainingTokens() <= 1) {
            final String literalValue = commandInput.peekString().replace(" ", "");
            for (final CommandNode<C> node : staticArguments) {
                suggestionFuture = suggestionFuture
                        .thenCompose(ctx -> this.addSuggestionsForLiteralArgument(context, node, literalValue));
            }
        }

        // Calculate suggestions for the variable argument, if one exists
        for (final CommandNode<C> child : root.children()) {
            if (child.component() == null || child.component().type() == CommandComponent.ComponentType.LITERAL) {
                continue;
            }
            suggestionFuture = suggestionFuture
                    .thenCompose(ctx -> this.addSuggestionsForDynamicArgument(context, commandInput, child));
        }

        return suggestionFuture;
    }

    /**
     * Adds the suggestions for a static argument if they match the given {@code input}
     *
     * @param context the suggestion context
     * @param node    the node containing the static argument
     * @param input   the current input
     * @return future that completes with the context
     */
    private CompletableFuture<SuggestionContext<C>> addSuggestionsForLiteralArgument(
            final @NonNull SuggestionContext<C> context,
            final @NonNull CommandNode<C> node,
            final @NonNull String input
    ) {
        if (this.findMissingPermission(context.commandContext().sender(), node) != null) {
            return CompletableFuture.completedFuture(context);
        }
        final CommandComponent<C> component = Objects.requireNonNull(node.component());
        context.commandContext().currentComponent(component);
        return component.suggestionProvider()
                .suggestionsFuture(context.commandContext(), input)
                .thenApply(suggestionsToAdd -> {
                    for (Suggestion suggestion : suggestionsToAdd) {
                        if (suggestion.suggestion().equals(input) || !suggestion.suggestion().startsWith(input)) {
                            continue;
                        }
                        context.addSuggestion(suggestion);
                    }
                    return context;
                });
    }

    @SuppressWarnings("unchecked")
    private @NonNull CompletableFuture<SuggestionContext<C>> addSuggestionsForDynamicArgument(
            final @NonNull SuggestionContext<C> context,
            final @NonNull CommandInput commandInput,
            final @NonNull CommandNode<C> child
    ) {
        final CommandComponent<C> component = child.component();
        if (component == null) {
            return CompletableFuture.completedFuture(context);
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        if (component.parser() instanceof AggregateCommandParser) {
            // If we're working with a compound argument then we attempt to pop the required arguments from the input queue.
            final AggregateCommandParser<C, ?> aggregateParser = (AggregateCommandParser<C, ?>) component.parser();
            future = this.popRequiredArguments(context.commandContext(), commandInput, aggregateParser);
        } else if (component.parser() instanceof CommandFlagParser) {
            // Use the flag argument parser to deduce what flag is being suggested right now
            // If empty, then no flag value is being typed, and the different flag options should
            // be suggested instead.
            final CommandFlagParser<C> parser = (CommandFlagParser<C>) component.parser();
            final Optional<String> lastFlag = parser.parseCurrentFlag(context.commandContext(), commandInput);
            if (lastFlag.isPresent()) {
                context.commandContext().store(CommandFlagParser.FLAG_META_KEY, lastFlag.get());
            } else {
                context.commandContext().remove(CommandFlagParser.FLAG_META_KEY);
            }
        } else if (commandInput.remainingTokens() <= component.parser().getRequestedArgumentCount()) {
            // If the input queue contains fewer arguments than requested by the parser, then the parser will
            // need to be given the opportunity to provide suggestions. We store all provided arguments
            // so that the parser can use these to give contextual suggestions.
            for (int i = 0; i < component.parser().getRequestedArgumentCount() - 1
                    && commandInput.remainingTokens() > 1; i++) {
                context.commandContext().store(
                        String.format("%s_%d", component.name(), i),
                        commandInput.readString()
                );
            }
        }

        return future.thenCompose(ignored -> {
            if (commandInput.isEmpty()) {
                return CompletableFuture.completedFuture(context);
            } else if (commandInput.remainingTokens() == 1) {
                return this.addArgumentSuggestions(context, child, commandInput.peekString());
            } else if (child.isLeaf() && child.component().parser() instanceof AggregateCommandParser) {
                return this.addArgumentSuggestions(context, child, commandInput.lastRemainingToken());
            }

            // Store original input command queue before the parsers below modify it
            final CommandInput commandInputOriginal = commandInput.copy();

            // START: Preprocessing
            final ArgumentParseResult<Boolean> preParseResult = component.preprocess(
                    context.commandContext(),
                    commandInput
            );
            final boolean preParseSuccess = !preParseResult.failure().isPresent()
                    && preParseResult.parsedValue().orElse(false);
            // END: Preprocessing

            final CompletableFuture<SuggestionContext<C>> parsingFuture;
            if (!preParseSuccess) {
                parsingFuture = CompletableFuture.completedFuture(null);
            } else {
                // START: Parsing
                context.commandContext().currentComponent(child.component());
                final CommandInput preParseInput = commandInput.copy();

                parsingFuture = child.component()
                        .parser()
                        .parseFuture(context.commandContext(), commandInput)
                        .thenApply(ArgumentParseResult::success)
                        .thenCompose(result -> {
                            final Optional<?> parsedValue = result.parsedValue();
                            final boolean parseSuccess = parsedValue.isPresent();

                            if (result.failure().isPresent()) {
                                commandInput.cursor(preParseInput.cursor());
                            }

                            // It's the last node, we don't care for success or not as we don't need to delegate to a child
                            if (child.isLeaf()) {
                                if (!commandInput.isEmpty()) {
                                    return CompletableFuture.completedFuture(context);
                                }

                                // Greedy parser took all the input, we can restore and just ask for suggestions
                                commandInput.cursor(commandInputOriginal.cursor());
                                this.addArgumentSuggestions(context, child, commandInput.remainingInput());
                            }

                            if (parseSuccess && !commandInput.isEmpty()) {
                                // the current argument at the position is parsable and there are more arguments following
                                context.commandContext().store(child.component().name(), parsedValue.get());
                                return this.getSuggestions(context, commandInput, child);
                            } else if (!parseSuccess && commandInputOriginal.remainingTokens() > 1) {
                                // at this point there should normally be no need to reset the command queue as we expect
                                // users to only take out an argument if the parse succeeded. Just to be sure we reset anyway
                                commandInput.cursor(commandInputOriginal.cursor());

                                // there are more arguments following but the current argument isn't matching - there
                                // is no need to collect any further suggestions
                                return CompletableFuture.completedFuture(context);
                            }
                            return CompletableFuture.completedFuture(null);
                        });
            }

            return parsingFuture.thenCompose(previousResult -> {
                if (previousResult != null) {
                    return CompletableFuture.completedFuture(previousResult);
                }

                // Restore original command input queue
                commandInput.cursor(commandInputOriginal.cursor());

                if (!preParseSuccess && commandInput.remainingTokens() > 1) {
                    // The preprocessor denied the argument, and there are more arguments following the current one
                    // Therefore we shouldn't list the suggestions of the current argument, as clearly the suggestions of
                    // one of the following arguments is requested
                    return CompletableFuture.completedFuture(context);
                }

                return this.addArgumentSuggestions(context, child, commandInput.peekString());
            });
        });
    }

    /**
     * Removes as many arguments from the {@code commandQueue} as the given {@code aggregateParser} requires. If the
     * {@code commandQueue} fewer than the required arguments then no arguments are popped
     *
     * @param commandContext  the command context
     * @param commandInput    the input
     * @param aggregateParser the aggregate parser
     * @return future that completes when the arguments have been popped
     */
    private CompletableFuture<Void> popRequiredArguments(
            final @NonNull CommandContext<C> commandContext,
            final @NonNull CommandInput commandInput,
            final @NonNull AggregateCommandParser<C, ?> aggregateParser
    ) {
        final int requiredArguments = aggregateParser.getRequestedArgumentCount();
        if (commandInput.remainingTokens() > requiredArguments) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        final List<CommandComponent<C>> components = aggregateParser.components();
        // We try to pop the input that would get parsed the inner components, but not the last one. We know that we have input
        // that will be parsed by one of the inner components. If no parser before the last one parses the input, then we
        // know for sure that the last one should capture all remaining input.
        //
        // We make sure to leave one string in the command input, as it should be passed to the suggestion method.
        for (int argumentCount = 0; argumentCount < components.size() - 1 && commandInput.remainingTokens() > 1; argumentCount++) {
            final CommandComponent<C> component = components.get(argumentCount);
            future = future.thenCompose(previousResult -> {
                        if (commandInput.remainingTokens() <= 1) {
                            return CompletableFuture.completedFuture(previousResult);
                        }
                        return component.parser().parseFuture(commandContext, commandInput).thenApply(result -> {
                            commandContext.store(component.name(), result);
                            return null;
                        });
                    });
        }
        return future;
    }

    /**
     * Adds the suggestions for the given {@code node} to the given {@code context}. If the {@code node} contains
     * a flag, then all children of the {@code node} will contribute with suggestions as well
     *
     * @param context the suggestion context
     * @param node    the node containing the argument to get suggestions from
     * @param text    the input from the sender
     * @return the context
     */
    private @NonNull CompletableFuture<SuggestionContext<C>> addArgumentSuggestions(
            final @NonNull SuggestionContext<C> context,
            final @NonNull CommandNode<C> node,
            final @NonNull String text
    ) {
        final CommandComponent<C> component = Objects.requireNonNull(node.component());
        return this.addArgumentSuggestions(context, component, text).thenCompose(ctx -> {
            // When suggesting a flag, potentially suggest following nodes too
            final boolean isParsingFlag = component.type() == CommandComponent.ComponentType.FLAG
                    && !node.children().isEmpty() // Has children
                    && !text.startsWith("-") // Not a flag
                    && !context.commandContext().optional(CommandFlagParser.FLAG_META_KEY).isPresent();

            if (!isParsingFlag) {
                return CompletableFuture.completedFuture(ctx);
            }

            return CompletableFuture.allOf(
                    node.children()
                            .stream()
                            .map(child -> this.addArgumentSuggestions(context, Objects.requireNonNull(child.component()), text))
                            .toArray(CompletableFuture[]::new)
            ).thenApply(v -> ctx);
        });
    }

    /**
     * Adds the suggestions for the given {@code argument} to the given {@code context}
     *
     * @param context   the suggestion context
     * @param component the component to get suggestions from
     * @param text      the input from the sender
     * @return future that completes with the context
     */
    private CompletableFuture<SuggestionContext<C>> addArgumentSuggestions(
            final @NonNull SuggestionContext<C> context,
            final @NonNull CommandComponent<C> component,
            final @NonNull String text
    ) {
        context.commandContext().currentComponent(component);
        return component.suggestionProvider()
                .suggestionsFuture(context.commandContext(), text)
                .thenAccept(context::addSuggestions)
                .thenApply(in -> context);
    }

    /**
     * Inserts a new command into the command tree and then verifies the integrity of the tree
     *
     * @param command the command to insert
     */
    @SuppressWarnings("unchecked")
    public void insertCommand(final @NonNull Command<C> command) {
        synchronized (this.commandLock) {
            final CommandComponent<C> flagComponent = command.flagComponent();
            final List<CommandComponent<C>> nonFlagArguments = command.nonFlagArguments();
            final int flagStartIdx = this.flagStartIndex(nonFlagArguments);

            CommandNode<C> node = this.internalTree;
            for (int i = 0; i < nonFlagArguments.size(); i++) {
                final CommandComponent<C> component = nonFlagArguments.get(i);

                CommandNode<C> tempNode = node.getChild(component);
                if (tempNode == null) {
                    tempNode = node.addChild(component);
                } else if (component.type() == CommandComponent.ComponentType.LITERAL && tempNode.component() != null) {
                    for (final String alias : component.aliases()) {
                        ((LiteralParser<C>) tempNode.component().parser()).insertAlias(alias);
                    }
                }
                if (!node.children().isEmpty()) {
                    node.sortChildren();
                }
                tempNode.parent(node);
                node = tempNode;

                if (flagComponent != null && i >= flagStartIdx) {
                    tempNode = node.addChild(flagComponent);
                    tempNode.parent(node);
                    node = tempNode;
                }
            }

            final CommandComponent<C> nodeComponent = node.component();
            if (nodeComponent != null) {
                if (nodeComponent.owningCommand() != null) {
                    throw new IllegalStateException(String.format(
                            "Duplicate command chains detected. Node '%s' already has an owning command (%s)",
                            node, nodeComponent.owningCommand()
                    ));
                }

                nodeComponent.owningCommand(command);
            }

            this.verifyAndRegister();
        }
    }

    /**
     * Returns the index of the given {@code components} list after which flags may be inserted.
     *
     * @param components the components
     * @return the index after which flags may be inserted
     */
    private int flagStartIndex(final @NonNull List<CommandComponent<C>> components) {
        // Append flags after the last static argument
        if (this.commandManager.settings().get(ManagerSetting.LIBERAL_FLAG_PARSING)) {
            for (int i = components.size() - 1; i >= 0; i--) {
                if (components.get(i).type() == CommandComponent.ComponentType.LITERAL) {
                    return i;
                }
            }
        }

        // Append flags after the last argument
        return components.size() - 1;
    }

    /**
     * Returns the permission that is missing from the given {@code sender} for them to execute the command attached
     * to the given {@code node}. If the {@code sender} is allowed to execute the command, the method returns {@code null}
     *
     * @param sender the command sender
     * @param node   the command node
     * @return the missing permission, or {@code null}
     */
    private @Nullable Permission findMissingPermission(
            final @NonNull C sender,
            final @NonNull CommandNode<C> node
    ) {
        final Permission permission = (Permission) node.nodeMeta().get("permission");
        if (permission != null) {
            return this.commandManager.hasPermission(sender, permission) ? null : permission;
        }
        if (node.isLeaf()) {
            final CommandComponent<C> component = Objects.requireNonNull(node.component(), "component");
            final Command<C> command = Objects.requireNonNull(component.owningCommand(), "command");
            return this.commandManager.hasPermission(
                    sender,
                    command.commandPermission()
            ) ? null : command.commandPermission();
        }
        /*
          if any of the children would permit the execution, then the sender has a valid
           chain to execute, and so we allow them to execute the root
         */
        final List<Permission> missingPermissions = new LinkedList<>();
        for (final CommandNode<C> child : node.children()) {
            final Permission check = this.findMissingPermission(sender, child);
            if (check == null) {
                return null;
            } else {
                missingPermissions.add(check);
            }
        }

        return Permission.anyOf(missingPermissions);
    }

    /**
     * Goes through all commands and registers them, then verifies the integrity of the command tree.
     */
    public void verifyAndRegister() {
        // All top level commands are supposed to be registered in the command manager
        this.internalTree.children().stream().map(CommandNode::component).forEach(component -> {
            if (component.type() != CommandComponent.ComponentType.LITERAL) {
                throw new IllegalStateException("Top level command argument cannot be a variable");
            }
        });

        this.checkAmbiguity(this.internalTree);

        // Verify that all leaf nodes have command registered
        this.getLeaves(this.internalTree).forEach(leaf -> {
            if (leaf.component().owningCommand() == null) {
                throw new NoCommandInLeafException(leaf.component());
            } else {
                final Command<C> owningCommand = leaf.component().owningCommand();
                this.commandManager.commandRegistrationHandler().registerCommand(owningCommand);
            }
        });

        this.getLeavesRaw(this.internalTree).forEach(this::updatePermission);
    }

    /**
     * Updates the permission of the given {@code node} by traversing the command tree and calculating the applicable permission.
     *
     * @param node the command node
     */
    private void updatePermission(final @NonNull CommandNode<C> node) {
        // noinspection all
        final Permission commandPermission = node.component().owningCommand().commandPermission();
        /* All leaves must necessarily have an owning command */
        node.nodeMeta().put("permission", commandPermission);
        // Get chain and order it tail->head then skip the tail (leaf node)
        List<CommandNode<C>> chain = this.getChain(node);
        Collections.reverse(chain);
        chain = chain.subList(1, chain.size());
        // Go through all nodes from the tail upwards until a collision occurs
        for (final CommandNode<C> commandArgumentNode : chain) {
            final Permission existingPermission = (Permission) commandArgumentNode.nodeMeta()
                    .get("permission");

            Permission permission;
            if (existingPermission != null) {
                permission = Permission.anyOf(commandPermission, existingPermission);
            } else {
                permission = commandPermission;
            }

            /* Now also check if there's a command handler attached to an upper level node */
            if (commandArgumentNode.component() != null && commandArgumentNode.component().owningCommand() != null) {
                final Command<C> command = commandArgumentNode.component().owningCommand();
                if (this.commandManager().settings().get(ManagerSetting.ENFORCE_INTERMEDIARY_PERMISSIONS)) {
                    permission = command.commandPermission();
                } else {
                    permission = Permission.anyOf(permission, command.commandPermission());
                }
            }

            commandArgumentNode.nodeMeta().put("permission", permission);
        }
    }

    /**
     * Verifies that there is no illegal ambiguity in the given {@code node}.
     *
     * @param node the node
     * @throws AmbiguousNodeException if the node breaks some ambiguity contract
     */
    private void checkAmbiguity(final @NonNull CommandNode<C> node) throws AmbiguousNodeException {
        if (node.isLeaf()) {
            return;
        }

        // List of child nodes that are not static arguments, but (parsed) variable ones
        final List<CommandNode<C>> childVariableArguments = node.children()
                .stream()
                .filter(n -> n.component() != null)
                .filter(n -> n.component().type() != CommandComponent.ComponentType.LITERAL)
                .collect(Collectors.toList());

        // If more than one child node exists with a variable argument, fail
        if (childVariableArguments.size() > 1) {
            final CommandNode<C> child = childVariableArguments.get(0);

            throw new AmbiguousNodeException(
                    node,
                    child,
                    node.children()
                            .stream()
                            .filter(n -> n.component() != null)
                            .collect(Collectors.toList())
            );
        }

        // List of child nodes that are static arguments, with fixed values
        final List<CommandNode<C>> childStaticArguments = node.children()
                .stream()
                .filter(n -> n.component() != null)
                .filter(n -> n.component().type() == CommandComponent.ComponentType.LITERAL)
                .collect(Collectors.toList());

        // Check none of the static arguments are equal to another one
        // This is done by filling a set and checking there are no duplicates
        final Set<String> checkedLiterals = new HashSet<>();
        for (final CommandNode<C> child : childStaticArguments) {
            for (final String nameOrAlias : child.component().aliases()) {
                if (!checkedLiterals.add(nameOrAlias)) {
                    // Same literal value, ambiguity detected
                    throw new AmbiguousNodeException(
                            node,
                            child,
                            node.children()
                                    .stream()
                                    .filter(n -> n.component() != null)
                                    .collect(Collectors.toList())
                    );
                }
            }
        }

        // Recursively check child nodes as well
        node.children().forEach(this::checkAmbiguity);
    }

    /**
     * Returns all leaf nodes attached to the given {@code node} or its children
     *
     * @param node the node
     * @return the leaf nodes attached to the node
     */
    private @NonNull List<@NonNull CommandNode<C>> getLeavesRaw(
            final @NonNull CommandNode<C> node
    ) {
        final List<CommandNode<C>> leaves = new LinkedList<>();
        if (node.isLeaf()) {
            if (node.component() != null) {
                leaves.add(node);
            }
        } else {
            node.children().forEach(child -> leaves.addAll(this.getLeavesRaw(child)));
        }
        return leaves;
    }

    /**
     * Returns all leaf nodes attached to the given {@code node} or its children
     *
     * @param node the node
     * @return the leaf nodes attached to the node
     */
    private @NonNull List<@NonNull CommandNode<C>> getLeaves(
            final @NonNull CommandNode<C> node
    ) {
        return this.getLeavesRaw(node).stream()
                .filter(n -> n.component() != null)
                .collect(Collectors.toList());
    }

    /**
     * Returns an ordered list containing the chain of nodes that leads up to the given {@code end} node
     *
     * @param end the end node
     * @return the list of nodes leading up to the {@code end} node
     */
    private @NonNull List<@NonNull CommandNode<C>> getChain(
            final @Nullable CommandNode<C> end
    ) {
        final List<CommandNode<C>> chain = new LinkedList<>();
        CommandNode<C> tail = end;
        while (tail != null) {
            chain.add(tail);
            tail = tail.parent();
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Recursively deletes the given {@code node} and its children and performs an operation on each account encountered during
     * the deletion
     *
     * @param node            the node to delete
     * @param root            whether the node is a root node
     * @param commandConsumer consumer of encountered commands
     */
    void deleteRecursively(
        final @NonNull CommandNode<C> node,
        final boolean root,
        final Consumer<Command<C>> commandConsumer
    ) {
        for (final CommandNode<C> child : new ArrayList<>(node.children())) {
            this.deleteRecursively(child, false, commandConsumer);
        }

        final @Nullable CommandComponent<C> component = node.component();
        final @Nullable Command<C> owner = component == null ? null : component.owningCommand();
        if (owner != null) {
            commandConsumer.accept(owner);
        }
        this.removeNode(node, root);
    }

    /**
     * Removes the {@code node} from the tree. If {@code root} is true, the code is removed from the root node. Otherwise,
     * it is removed from its parent
     *
     * @param node the node to remove
     * @param root whether the node is a root node
     */
    private void removeNode(
        final @NonNull CommandNode<C> node,
        final boolean root
    ) {
        if (root) {
            this.internalTree.removeChild(node);
        } else {
            Objects.requireNonNull(node.parent(), "parent").removeChild(node);
        }
    }

    private static @NonNull List<@NonNull String> tokenize(final @NonNull CommandInput commandInput) {
        return new CommandInputTokenizer(commandInput.remainingInput()).tokenize();
    }
}
