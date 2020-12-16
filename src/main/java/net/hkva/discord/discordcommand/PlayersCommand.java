package net.hkva.discord.discordcommand;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.hkva.discord.DiscordCommandManager;
import net.hkva.discord.DiscordIntegrationMod;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.awt.*;

public class PlayersCommand {

    private static final int MAX_LIST = 20;

    public static void register(CommandDispatcher<Message> dispatcher) {
        dispatcher.register(
                DiscordCommandManager.literal("players").executes(PlayersCommand::playersCommand));
    }
    
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
    
    public static int playersCommand(CommandContext<Message> context) {
        DiscordIntegrationMod.withServer(s -> {
            final EmbedBuilder e = new EmbedBuilder();
            e.setColor(Color.GREEN);
            e.setTitle(String.format("%d/%d players online", s.getCurrentPlayerCount() - onlineStaffCount(s.getPlayerManager()), s.getMaxPlayerCount()));

            final PlayerManager players = s.getPlayerManager();

            int playersInList = 0;
            for (ServerPlayerEntity player : players.getPlayerList()) {
                if (!isPlayerStaff(player.getName().getString())) {
                    e.appendDescription(DiscordIntegrationMod.escapeDiscordFormatting(player.getName().getString())
                        + "\n");
                }
                if (++playersInList == MAX_LIST) {
                    break;
                }
            }

            if (playersInList != s.getCurrentPlayerCount()) {
                e.appendDescription(String.format("...and %d more", s.getCurrentPlayerCount() - onlineStaffCount(s.getPlayerManager()) - playersInList));
            }

            context.getSource().getTextChannel().sendMessage(e.build()).queue();
        });
        return 0;
    }
}
