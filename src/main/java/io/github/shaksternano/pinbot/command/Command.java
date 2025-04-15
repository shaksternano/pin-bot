package io.github.shaksternano.pinbot.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface Command {

    String execute(SlashCommandInteractionEvent event);

    Collection<OptionData> getOptions();

    Optional<String> getGroup();

    DefaultMemberPermissions getPermissions();

    List<InteractionContextType> getContexts();

    String getName();

    String getDescription();
}
