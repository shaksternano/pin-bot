package io.github.shaksternano.pinbot.command;

import io.github.shaksternano.pinbot.Database;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PinChannelListCommand extends PinChannelSubCommand {

    public static final PinChannelListCommand INSTANCE = new PinChannelListCommand();

    private PinChannelListCommand() {
        super("list", "Lists all channels that are currently sending pins to another channel.");
    }

    @Override
    public String execute(SlashCommandInteractionEvent event) {
        var guild = getGuild(event);
        var guildPinChannels = Database.getPinChannels(guild.getIdLong());
        if (guildPinChannels.isEmpty()) {
            return "There are currently no pin channels set.";
        } else {
            return "Pin channels:\n" + guildPinChannels.entrySet()
                .stream()
                .map(entry -> new ChannelPair(entry, guild))
                .filter(ChannelPair::isValid)
                .map(ChannelPair::pinChannelListEntry)
                .collect(Collectors.joining("\n"));
        }
    }

    @Override
    public Collection<OptionData> getOptions() {
        return List.of();
    }

    private record ChannelPair(@Nullable Channel sendPinFrom, @Nullable Channel sendPinTo) {

        private ChannelPair(Map.Entry<Long, Long> entry, Guild guild) {
            this(
                guild.getChannelById(GuildChannel.class, entry.getKey()),
                guild.getChannelById(GuildChannel.class, entry.getValue())
            );
        }

        private boolean isValid() {
            return sendPinFrom != null && sendPinTo != null;
        }

        private String pinChannelListEntry() {
            return sendPinFrom().getAsMention() + " -> " + sendPinTo().getAsMention();
        }

        @Override
        public Channel sendPinFrom() {
            if (sendPinFrom == null) {
                throw new IllegalStateException("sendPinFrom is null");
            } else {
                return sendPinFrom;
            }
        }

        @Override
        public Channel sendPinTo() {
            if (sendPinTo == null) {
                throw new IllegalStateException("sendPinTo is null");
            } else {
                return sendPinTo;
            }
        }
    }
}
