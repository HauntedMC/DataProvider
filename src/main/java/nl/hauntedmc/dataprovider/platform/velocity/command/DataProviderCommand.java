package nl.hauntedmc.dataprovider.platform.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.internal.command.DataProviderCommandService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class DataProviderCommand implements SimpleCommand {

    private final DataProviderCommandService commandService;

    public DataProviderCommand(DataProviderHandler dataProviderHandler) {
        this(new DataProviderCommandService(dataProviderHandler));
    }

    DataProviderCommand(DataProviderCommandService commandService) {
        this.commandService = Objects.requireNonNull(commandService, "Command service cannot be null.");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        commandService.execute(invocation.arguments(), source::hasPermission, source::sendMessage);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(commandService.suggest(invocation.arguments()));
    }
}
