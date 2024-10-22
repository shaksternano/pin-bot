package io.github.shaksternano.pinbot;

import com.google.common.collect.Lists;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import okhttp3.MediaType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class PinnedMessageForwarder {

    public static void sendCustomPinConfirmationIfPinChannelSet(MessageReceivedEvent event) {
        var pinConfirmation = event.getMessage();
        var sentFrom = event.getChannel();
        if (pinConfirmation.getType().equals(MessageType.CHANNEL_PINNED_ADD)) {
            Database.getPinChannel(sentFrom.getIdLong())
                .ifPresent(pinChannelId -> sendCustomPinConfirmation(pinConfirmation, pinChannelId));
        }
    }

    private static void sendCustomPinConfirmation(Message pinConfirmation, long pinChannelId) {
        var pinnedMessageReference = pinConfirmation.getMessageReference();
        if (pinnedMessageReference == null) {
            Main.getLogger().error("System pin confirmation message has no message reference.");
        } else {
            var sentFrom = pinConfirmation.getChannel();
            var pinner = pinConfirmation.getAuthor();
            pinConfirmation.delete().queue();
            sentFrom.retrieveMessageById(pinnedMessageReference.getMessageId())
                .submit()
                .thenCompose(pinnedMessage -> sendPinConfirmation(sentFrom, pinChannelId, pinner, pinnedMessage))
                .whenComplete((unused, throwable) -> handleError(throwable, pinConfirmation));
        }
    }

    private static CompletableFuture<Message> sendPinConfirmation(MessageChannel sendTo, long pinChannelId, User pinner, Message pinnedMessage) {
        var pinChannel = sendTo.getJDA().getChannelById(Channel.class, pinChannelId);
        if (pinChannel == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No pin channel set for " + sendTo + "."));
        } else {
            try (var customPinConfirmation = createPinConfirmationMessage(pinner, pinChannel, pinnedMessage)) {
                return sendTo.sendMessage(customPinConfirmation).submit();
            }
        }
    }

    private static MessageCreateData createPinConfirmationMessage(User pinner, Channel pinChannel, Message pinnedMessage) {
        return new MessageCreateBuilder()
            .addContent(pinner.getAsMention() + " pinned a message to " + pinChannel.getAsMention() + ".")
            .addActionRow(Button.link(pinnedMessage.getJumpUrl(), "Jump to message"))
            .build();
    }

    public static void forwardMessageIfPinned(MessageUpdateEvent event) {
        var message = event.getMessage();
        if (message.isPinned()) {
            Database.getPinChannel(event.getChannel().getIdLong())
                .ifPresent(pinChannelId -> forwardPinnedMessage(message, pinChannelId));
        }
    }

    private static void forwardPinnedMessage(Message message, long pinChannelId) {
        var sentFrom = message.getChannel();
        var pinChannel = message.getGuild().getChannelById(GuildChannel.class, pinChannelId);
        getWebhookContainer(pinChannel).ifPresentOrElse(
            webhookContainer -> webhookContainer.retrieveWebhooks()
                .submit()
                .thenCompose(webhooks -> getOrCreateWebhook(webhooks, webhookContainer))
                .thenApply(webhook -> getWebhookUrl(webhook, pinChannel))
                .thenCompose(webhook -> forwardMessageToWebhook(message, webhook))
                .whenComplete((unused, throwable) -> handleError(throwable, message)),
            () -> handleNoWebhookSupport(sentFrom, pinChannel)
        );
    }

    private static Optional<IWebhookContainer> getWebhookContainer(@Nullable Channel channel) {
        if (channel instanceof ThreadChannel threadChannel) {
            channel = threadChannel.getParentChannel();
        }
        if (channel instanceof IWebhookContainer webhookContainer) {
            return Optional.of(webhookContainer);
        } else {
            return Optional.empty();
        }
    }

    private static CompletableFuture<Webhook> getOrCreateWebhook(Collection<Webhook> webhooks, IWebhookContainer webhookContainer) {
        return getOwnWebhook(webhooks)
            .map(CompletableFuture::completedFuture)
            .orElseGet(() -> createWebhook(webhookContainer));
    }

    private static String getWebhookUrl(Webhook webhook, Channel sendTo) {
        var webhookUrl = webhook.getUrl();
        if (sendTo instanceof ThreadChannel) {
            webhookUrl += "?thread_id=" + sendTo.getId();
        }
        return webhookUrl;
    }

    private static Optional<Webhook> getOwnWebhook(Collection<Webhook> webhooks) {
        return webhooks.stream()
            .filter(PinnedMessageForwarder::isOwnWebhook)
            .findAny();
    }

    private static boolean isOwnWebhook(Webhook webhook) {
        return webhook.getJDA().getSelfUser().equals(webhook.getOwnerAsUser());
    }

    private static CompletableFuture<Webhook> createWebhook(IWebhookContainer webhookContainer) {
        var icon = getIcon(webhookContainer.getJDA().getSelfUser()).orElse(null);
        return createWebhook(webhookContainer, icon);
    }

    private static Optional<Icon> getIcon(User user) {
        var avatarUrl = user.getEffectiveAvatarUrl();
        try (var iconStream = new URI(avatarUrl).toURL().openStream()) {
            return Optional.of(Icon.from(iconStream));
        } catch (IOException | URISyntaxException e) {
            Main.getLogger().error("Failed to create icon for " + user + ".", e);
            return Optional.empty();
        }
    }

    private static CompletableFuture<Webhook> createWebhook(IWebhookContainer webhookContainer, @Nullable Icon icon) {
        return webhookContainer.createWebhook(webhookContainer.getJDA().getSelfUser().getName())
            .setAvatar(icon)
            .submit();
    }

    private static CompletableFuture<Void> forwardMessageToWebhook(Message message, String webhookUrl) {
        var author = message.getAuthor();
        var guild = message.getGuild();
        return retrieveUserDetails(author, guild).thenCompose(userDetails -> {
            var messageSendFuture = createAndSendWebhookMessage(message, userDetails.username(), userDetails.avatarUrl(), webhookUrl);
            var unpinFuture = unpinOriginalMessage(message);
            return CompletableFuture.allOf(messageSendFuture, unpinFuture);
        });
    }

    private static CompletableFuture<UserDetails> retrieveUserDetails(User author, Guild guild) {
        if (Database.usesGuildProfile(guild.getIdLong())) {
            return guild.retrieveMember(author)
                .submit()
                .thenApply(UserDetails::new)
                .exceptionally(throwable -> new UserDetails(author));
        } else {
            return CompletableFuture.completedFuture(new UserDetails(author));
        }
    }

    private static CompletableFuture<Void> createAndSendWebhookMessage(Message message, String username, String avatarUrl, String webhookUrl) {
        var client = WebhookClient.createClient(message.getJDA(), webhookUrl);
        var messageFutures = createWebhookMessages(message);
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        for (var messageFuture : messageFutures) {
            future = future.thenCompose(unused -> messageFuture)
                .thenCompose(messageData -> client.sendMessage(messageData)
                    .setUsername(username)
                    .setAvatarUrl(avatarUrl)
                    .submit()
                )
                .thenApply(unused -> null);
        }
        return future;
    }

    private static List<CompletableFuture<MessageCreateData>> createWebhookMessages(Message message) {
        var messageData = getMessageData(message);
        return createPinnedMessages(messageData.messageContent(), messageData.attachments(), message);
    }

    private static MessageData getMessageData(Message message) {
        var messageContentBuilder = new StringBuilder(message.getContentRaw());
        List<Message.Attachment> attachments = new ArrayList<>();
        long maxFileSize;
        if (message.isFromGuild()) {
            maxFileSize = message.getGuild().getMaxFileSize();
        } else {
            maxFileSize = Message.MAX_FILE_SIZE;
        }
        for (var attachment : message.getAttachments()) {
            if (attachment.getSize() <= maxFileSize && reUploadAttachment(attachment)) {
                attachments.add(attachment);
            } else {
                String attachmentUrl;
                var fileExtension = attachment.getFileExtension();
                if (fileExtension != null && fileExtension.equalsIgnoreCase("gif")) {
                    attachmentUrl = attachment.getProxyUrl();
                } else {
                    attachmentUrl = attachment.getUrl();
                }
                messageContentBuilder.append("\n").append(attachmentUrl.split("\\?")[0]);
            }
        }
        for (var sticker : message.getStickers()) {
            messageContentBuilder.append("\n").append(sticker.getIconUrl());
        }
        return new MessageData(messageContentBuilder.toString(), attachments);
    }

    private static boolean reUploadAttachment(Message.Attachment attachment) {
        var fileExtension = attachment.getFileExtension();
        return fileExtension == null || !fileExtension.equalsIgnoreCase("gif");
    }

    private static List<CompletableFuture<MessageCreateData>> createPinnedMessages(String content, List<Message.Attachment> attachments, Message originalMessage) {
        var messageContents = SplitUtil.split(
            content,
            Message.MAX_CONTENT_LENGTH,
            true,
            SplitUtil.Strategy.NEWLINE,
            SplitUtil.Strategy.WHITESPACE,
            SplitUtil.Strategy.ANYWHERE
        );
        var partitionedAttachments = Lists.partition(attachments, Message.MAX_FILE_AMOUNT);
        List<CompletableFuture<MessageCreateData>> messageFutures = new ArrayList<>();
        var maxSize = Math.max(messageContents.size(), partitionedAttachments.size());
        for (var i = 0; i < maxSize; i++) {
            var messageBuilder = new MessageCreateBuilder();
            if (i == maxSize - 1) {
                messageBuilder.addComponents(ActionRow.of(Button.link(originalMessage.getJumpUrl(), "Original message")));
            }
            CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
            if (i < messageContents.size()) {
                var messageContent = messageContents.get(i);
                messageBuilder.addContent(messageContent);
            }
            if (i < partitionedAttachments.size()) {
                var messageAttachments = partitionedAttachments.get(i);
                for (var attachment : messageAttachments) {
                    var inputStreamFuture = attachment.getProxy().download();
                    future = future.thenCompose(unused -> inputStreamFuture)
                        .thenAccept(inputStream -> {
                            var upload = FileUpload.fromData(inputStream, attachment.getFileName());
                            var contentType = attachment.getContentType();
                            if (contentType != null) {
                                var mediaType = MediaType.parse(contentType);
                                var waveform = attachment.getWaveform();
                                if (mediaType != null && waveform != null) {
                                    upload.asVoiceMessage(mediaType, waveform, attachment.getDuration());
                                }
                            }
                            messageBuilder.addFiles(upload);
                        });
                }
            }
            messageFutures.add(future.thenApply(unused -> messageBuilder.build()));
        }
        return messageFutures;
    }

    private static CompletableFuture<Void> unpinOriginalMessage(Message pinned) {
        return pinned.unpin().submit();
    }

    private static void handleError(@Nullable Throwable throwable, Message message) {
        if (throwable != null) {
            if (isInvalidFormBody(throwable)) {
                handleBannedUsername(message);
            } else {
                handleGenericError(throwable, message);
            }
        }
    }

    private static boolean isInvalidFormBody(Throwable throwable) {
        return getErrorResponse(throwable)
            .map(errorResponse -> errorResponse == ErrorResponse.INVALID_FORM_BODY)
            .orElse(false);
    }

    private static Optional<ErrorResponse> getErrorResponse(Throwable throwable) {
        if (throwable instanceof CompletionException completionException) {
            var cause = completionException.getCause();
            if (cause instanceof ErrorResponseException errorResponseException) {
                return Optional.of(errorResponseException.getErrorResponse());
            }
        }
        return Optional.empty();
    }

    private static void handleBannedUsername(Message message) {
        var author = message.getAuthor();
        var guild = message.getGuild();
        retrieveUserDetails(author, guild).thenAccept(userDetails -> {
            var sentFrom = message.getChannel();
            sentFrom.sendMessage(
                "Failed to pin message as the username `"
                    + userDetails.username()
                    + "` is not allowed!"
            ).queue();
        });
    }

    private static void handleGenericError(Throwable throwable, Message message) {
        Main.getLogger().error("An error occurred while pinning a message", throwable);
        var sentFrom = message.getChannel();
        sentFrom.sendMessage("An error occurred while pinning this message.").queue();
    }

    private static void handleNoWebhookSupport(MessageChannel sentFrom, @Nullable Channel pinChannel) {
        if (pinChannel != null) {
            sentFrom.sendMessage(pinChannel.getAsMention() + " doesn't support webhooks.").queue();
        }
        Database.removeSendPinToChannel(sentFrom.getIdLong());
    }

    private record UserDetails(String username, String avatarUrl) {

        public UserDetails(User user) {
            this(user.getEffectiveName(), user.getEffectiveAvatarUrl());
        }

        public UserDetails(Member member) {
            this(member.getEffectiveName(), member.getEffectiveAvatarUrl());
        }
    }

    private record MessageData(String messageContent, List<Message.Attachment> attachments) {
    }
}
