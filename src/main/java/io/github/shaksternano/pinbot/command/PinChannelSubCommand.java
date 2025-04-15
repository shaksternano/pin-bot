package io.github.shaksternano.pinbot.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.util.List;
import java.util.Optional;

public abstract class PinChannelSubCommand extends BaseCommand {

    public PinChannelSubCommand(String name, String description) {
        super(name, description);
    }

    @Override
    public Optional<String> getGroup() {
        return Optional.of("pin-channel");
    }

    @Override
    public DefaultMemberPermissions getPermissions() {
        return DefaultMemberPermissions.enabledFor(
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_CHANNEL
        );
    }

    @Override
    public List<InteractionContextType> getContexts() {
        return List.of(InteractionContextType.GUILD);
    }

    protected Guild getGuild(SlashCommandInteractionEvent event) {
        var guild = event.getGuild();
        if (guild == null) {
            throw new IllegalArgumentException("Command used in a private channel (direct message).");
        } else {
            return guild;
        }
    }

    protected OptionMapping getRequiredOption(SlashCommandInteractionEvent event, String name) {
        var option = event.getOption(name);
        if (option == null) {
            throw new IllegalArgumentException("No option with name " + name + " provided.");
        } else {
            return option;
        }
    }

    protected Optional<OptionMapping> getOptionalOption(SlashCommandInteractionEvent event, String name) {
        return Optional.ofNullable(event.getOption(name));
    }
}
