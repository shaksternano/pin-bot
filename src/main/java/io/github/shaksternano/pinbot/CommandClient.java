package io.github.shaksternano.pinbot;

import io.github.shaksternano.pinbot.command.Command;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.*;
import java.util.stream.Collectors;

public class CommandClient {

    private static final Map<String, Command> commands = new HashMap<>();

    public static void addCommands(JDA jda, Command command, Command... commands) {
        var commandList = new ArrayList<>(Arrays.asList(commands));
        commandList.add(command);

        var ungroupedCommands = commandList.stream()
            .filter(command1 -> command1.getGroup().isEmpty())
            .map(CommandClient::createUngroupedCommand)
            .toList();
        var groupedCommands = commandList.stream()
            .filter(command1 -> command1.getGroup().isPresent())
            .collect(Collectors.groupingBy(command1 -> command1.getGroup().orElseThrow()))
            .entrySet()
            .stream()
            .map(CommandClient::createGroupedCommand)
            .toList();

        jda.updateCommands()
            .addCommands(ungroupedCommands)
            .addCommands(groupedCommands)
            .queue();
        CommandClient.addCommands(command, commands);
    }

    private static SlashCommandData createUngroupedCommand(Command command) {
        return Commands.slash(command.getName(), command.getDescription())
            .setDefaultPermissions(command.getPermissions())
            .setContexts(command.getContexts())
            .addOptions(command.getOptions());
    }

    private static SlashCommandData createGroupedCommand(Map.Entry<String, List<Command>> commandGroup) {
        var group = commandGroup.getKey();
        var commands = commandGroup.getValue();
        var firstCommand = commands.getFirst();
        var permissions = firstCommand.getPermissions();
        var subCommands = commands.stream()
            .map(command -> new SubcommandData(command.getName(), command.getDescription())
                .addOptions(command.getOptions())
            ).toList();
        return Commands.slash(group, group)
            .setDefaultPermissions(permissions)
            .setContexts(firstCommand.getContexts())
            .addSubcommands(subCommands);
    }

    public static void handleCommand(SlashCommandInteractionEvent event) {
        var commandName = event.getName();
        if (event.getSubcommandName() != null) {
            commandName += " " + event.getSubcommandName();
        }
        var command = commands.get(commandName);
        if (command != null) {
            String response;
            try {
                response = command.execute(event);
            } catch (Throwable t) {
                response = "An error occurred while executing the command.";
                Main.getLogger().error("An error occurred while executing the command {}", command, t);
            }
            event.reply(response).setEphemeral(true).queue();
        }
    }

    private static void addCommands(Command command, Command... commands) {
        addCommand(command);
        for (var command1 : commands) {
            addCommand(command1);
        }
    }

    private static void addCommand(Command command) {
        var name = command.getName();
        var fullName = command.getGroup()
            .map(group -> group + " " + name)
            .orElse(name);
        commands.put(
            fullName,
            command
        );
    }
}
