package net.hkva.discord;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vdurmont.emoji.EmojiParser;
import net.dv8tion.jda.api.entities.*;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.server.ServerStartCallback;
import net.fabricmc.fabric.api.event.server.ServerStopCallback;
import net.fabricmc.fabric.api.event.server.ServerTickCallback;
import net.hkva.discord.callback.DiscordMessageCallback;
import net.hkva.discord.callback.ServerChatCallback;
import net.minecraft.network.MessageType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.Consumer;

public class DiscordIntegrationMod implements DedicatedServerModInitializer {

	// Just let everything be accessed statically
	// Not a big deal since there is only one instance of this class

	public static Logger LOGGER = LogManager.getLogger("Discord Integration");

	// Incoming message buffer
	public static List<String> messageBufferIn = Collections.synchronizedList(new ArrayList<String>());

	private static final ConfigFile configFile = new ConfigFile("discord.json");
	private static Config config = ConfigFile.DEFAULT_CONFIG;

	private static DiscordBot bot = new DiscordBot();

	private static Optional<MinecraftServer> server = Optional.empty();

	private static DiscordCommandManager commands = new DiscordCommandManager();

	private static int lastPlayerCount = -1;
	private static final int PLAYER_COUNT_UPDATE_INTERVAL = 20 * 5;

	/**
	 * Mod entry point
	 */
	@Override
	public void onInitializeServer() {
		if (!readConfig()) {
			LOGGER.warn("Not running since config was just created");
			LOGGER.warn("You can reload the config with /discord loadConfig");
			LOGGER.warn("Then, you can connect to Discord with /discord reconnect");
			return;
		}

		connectBot();

		ServerStartCallback.EVENT.register(DiscordIntegrationMod::onServerStart);
		ServerStopCallback.EVENT.register(DiscordIntegrationMod::onServerStop);
		ServerTickCallback.EVENT.register(DiscordIntegrationMod::onServerTick);
		ServerChatCallback.EVENT.register(DiscordIntegrationMod::onServerChat);
		DiscordMessageCallback.EVENT.register(DiscordIntegrationMod::onDiscordChat);
	}

	private static void onServerStart(MinecraftServer server) {
		DiscordIntegrationMod.server = Optional.of(server);
	}

	/**
	 * Server stop callback
	 */
	private static void onServerStop(MinecraftServer server) {
		DiscordIntegrationMod.server = Optional.empty();
		bot.disconnect();
	}

	/**
	 * Server tick callback
	 */
	private static boolean isPlayerStaff (String name) {
        String staffNames = "Distich LifeOnLoop Piggy_73 0a1";
        return staffNames.contains(name);
	}
	
	private static int onlineStaffCount (PlayerManager players) {
        int staffCounter = 0;
        for (ServerPlayerEntity player : players.getPlayerList()) {
            if (isPlayerStaff(player.getName().getString())) {
                staffCounter++;
            }
        }
        return staffCounter;
	}
	
	private static void onServerTick(MinecraftServer server) {
		// TODO: Replace with player join/leave listeners
		if (server.getTicks() % PLAYER_COUNT_UPDATE_INTERVAL == 0) {
			final int playerCount = server.getCurrentPlayerCount();
			bot.withConnection(c -> {
				if (lastPlayerCount != playerCount) {
					lastPlayerCount = playerCount;
					c.getPresence().setActivity(Activity.playing(String.format("%d/%d players",
							playerCount - onlineStaffCount(server.getPlayerManager()), server.getMaxPlayerCount())));
				}
			});
		}
	}

	/**
	 * On Minecraft chat message sent
	 */
	private static boolean staffInMessage (String message) {
		if (message.substring(0,1).equals("<")) {
			return false;
		}
		String distich = "Distich";
		String life = "LifeOnLoop";
		String pig = "Piggy_73";
		String geek = "0a1";
        return (message.contains(0a1) || message.contains(distich) || message.contains(life) || message.contains(pig) );
    }

	private static void onServerChat(MinecraftServer server, Text text, MessageType type, UUID senderUUID) {
		if (type == MessageType.CHAT && senderUUID == Util.NIL_UUID) {
			return;
		}

		String discordMessage = formatOutgoing(text.getString());

		bot.withConnection(c -> {
			for (Long channelID : config.relayChannelIDs) {
				final TextChannel relayChannel = c.getTextChannelById(channelID);
				if (relayChannel == null || !relayChannel.canTalk()) {
					LOGGER.warn("Relay channel " + channelID + " is invalid");
					continue;
				}
				if (!staffInMessage(discordMessage)) {
					relayChannel.sendMessage(formatGuildEmoji(discordMessage, relayChannel.getGuild())).queue();
				}
			}
		});
	}

	/**
	 * On Discord chat message sent
	 */
	private static void onDiscordChat(Message message) {
		if (!server.isPresent()) {
			return;
		}

		// Ignore pins, joins, boosts, etc
		if (message.getType() != net.dv8tion.jda.api.entities.MessageType.DEFAULT) {
			return;
		}

		final User author = message.getAuthor();

		if (author.isBot()) {
			return;
		}

		final String messageContent = message.getContentDisplay();
		if (messageContent.startsWith(config.commandPrefix) && message.getTextChannel().canTalk()) {
			final String messageNoPrefix = messageContent.substring(config.commandPrefix.length());
			try {
				commands.getDispatcher().execute(messageNoPrefix, message);
			} catch (CommandSyntaxException ignored) {}
			return;
		}

		if (!config.relayChannelIDs.contains((Long)message.getChannel().getIdLong())) {
			return;
		}

		server.get().getPlayerManager().broadcastChatMessage(
				formatIncoming(message),
				MessageType.CHAT,
				Util.NIL_UUID
		);
	}

	/**
	 * Register in-game commands
	 * @param dispatcher Server command dispatcher
	 */
	public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("discord")
				.requires(serverCommandSource -> serverCommandSource.hasPermissionLevel(4))
				.then(CommandManager.literal("loadConfig")
						.executes(DiscordIntegrationMod::reloadConfigCommand)
				)
				.then(CommandManager.literal("status")
						.executes(DiscordIntegrationMod::statusCommand)
				)
				.then(CommandManager.literal("reconnect")
						.executes(DiscordIntegrationMod::reconnectCommand)
				)
		);
	}

	/**
	 * In-game command to reload the config
	 */
	public static int reloadConfigCommand(CommandContext<ServerCommandSource> context) {
		String response = "Discord: Loaded config";
		if (!readConfig()) {
			response = "Discord: Wrote default config";
		}
		context.getSource().sendFeedback(new LiteralText(response), true);
		return 0;
	}

	/**
	 * In-game command to check the connection status
	 */
	public static int statusCommand(CommandContext<ServerCommandSource> context) {
		final ServerCommandSource source = context.getSource();
		if (!bot.isConnected()) {
			source.sendFeedback(new LiteralText("Discord: Not connected"), false);
		} else {
			bot.withConnection(c -> {
				source.sendFeedback(new LiteralText("Discord: Connected"), false);
				source.sendFeedback(new LiteralText("Status: " + c.getStatus()), false);
			});
		}

		return 0;
	}

	/**
	 * In-game command to reconnect to discord
	 */
	public static int reconnectCommand(CommandContext<ServerCommandSource> context) {
		final ServerCommandSource source = context.getSource();
		bot.disconnect();
		source.sendFeedback(new LiteralText("Discord: Disconnected"), true);
		if (!connectBot()) {
			source.sendFeedback(new LiteralText("Discord: Could not log in"), true);
		} else {
			source.sendFeedback(new LiteralText("Discord: Connected"), true);
		}

		return 0;
	}

	/**
	 * Load the config from disk
	 * @return false if the config didn't exist
	 */
	public static boolean readConfig() {
		boolean result = true;
		// Write default config if required
		if (!configFile.exists()) {
			LOGGER.warn("Config file " + configFile + " does not exist, creating...");
			configFile.writeDefaultConfig();
			result = false;
		}
		// Read config
		Optional<Config> readResult = configFile.readConfig();
		if (!readResult.isPresent()) {
			LOGGER.error("Failed to read config file " + configFile);
		} else {
			LOGGER.info("Read config " + configFile);
			config = readResult.get();
		}

		return result;
	}

	/**
	 * Connect to discord
	 */
	public static boolean connectBot() {
		if (bot.isConnected()) {
			throw new IllegalStateException("Bot is already connected");
		}

		try {
			bot.connect(config.token);
		} catch (Exception e) {
			LOGGER.warn("Discord: Could not log in with the supplied token");
			return false;
		}

		LOGGER.info("Discord: Logged in");
		return true;
	}

	/**
	 * Disconnect from discord
	 */
	public static void disconnectBot() {
		if (!bot.isConnected()) {
			throw new IllegalStateException("Bot is already disconnected");
		}

		bot.disconnect();
		LOGGER.info("Logged out of Discord");
	}

	/**
	 * Access the server
	 */
	public static void withServer(Consumer<MinecraftServer> action) {
		if (server.isPresent()) {
			action.accept(server.get());
		}
	}

	/**
	 * Format an outgoing message
	 */
	public static String formatOutgoing(String message) {
		// "@" -> "@ "
		return message.replace("@", "@ ");
	}

	/**
	 * Format an incoming message
	 */
	public static Text formatIncoming(Message message) {
		LiteralText text = new LiteralText(String.format("&aDISCORD &7| &f[%s] ", formatUsername(message.getAuthor())));

		// Add attachments as clickable text
		for (Message.Attachment a : message.getAttachments()) {
			final MutableText attachmentText = new LiteralText(a.getFileName());
			attachmentText.setStyle(attachmentText.getStyle()
					.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, a.getUrl()))
					.withFormatting(Formatting.BLUE)
					.withFormatting(Formatting.UNDERLINE)
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
							new LiteralText("Click to open in your web browser")))
			);
			text.append(attachmentText).append(" ");
		}

		text.append(EmojiParser.parseToAliases(message.getContentDisplay()));

		return text;
	}

	/**
	 * Format a user's name
	 */
	public static String formatUsername(User user) {
		return String.format("%s#%s", user.getName(), user.getDiscriminator());
	}

	/**
	 * Escape formatting characters like "_"
	 */
	public static String escapeDiscordFormatting(String str) {
		// TODO: Anything other than "_"
		return str.replaceAll("_", "\\_");
	}

	public static String formatGuildEmoji(String message, Guild guild) {
		for (Emote e : guild.getEmotes()) {
			final String emojiDisplay = String.format(":%s:", e.getName());
			final String emojiFormatted = String.format("<%s%s>", emojiDisplay, e.getId());
			message = message.replaceAll(emojiDisplay, emojiFormatted);
		}
		return message;
	}
}
