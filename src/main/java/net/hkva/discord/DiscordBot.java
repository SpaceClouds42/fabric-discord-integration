package net.hkva.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import javax.security.auth.login.LoginException;

import net.hkva.discord.callback.DiscordMessageCallback;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Represents a  connection to Discord
 */
public class DiscordBot extends ListenerAdapter {

    private Optional<JDA> connection = Optional.empty();

    /**
     * On Discord message received
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        DiscordMessageCallback.EVENT.invoker().dispatch(event.getMessage());
    }

    /**
     * Connect to discord
     */
    public void connect(final String token) throws LoginException, InterruptedException {
        final JDABuilder builder = JDABuilder.createDefault(token)
                .setStatus(OnlineStatus.ONLINE)
                .addEventListeners(this);
        connection = Optional.of(builder.build());
        connection.get().awaitReady();
    }

    /**
     * Disconnect from discord
     */
    public void disconnect() {
        withConnection(c -> c.shutdownNow());
        connection = Optional.empty();
    }

    /**
     * Check if the bot is connected
     */
    public boolean isConnected() {
        return connection.isPresent() && connection.get().getStatus() == JDA.Status.CONNECTED;
    }

    /**
     * Do something with the connection, if available
     */
    public boolean withConnection(Consumer<JDA> action) {
        if (!connection.isPresent()) {
            return false;
        }
        action.accept(connection.get());
        return true;
    }
}
