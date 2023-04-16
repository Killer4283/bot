/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.core.listeners.operations;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.kodehawa.mantarobot.core.listeners.operations.core.ButtonOperation;
import net.kodehawa.mantarobot.core.listeners.operations.core.Operation;
import net.kodehawa.mantarobot.utils.exporters.Metrics;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ButtonOperations {
    private static final EventListener LISTENER = new ButtonOperations.ButtonListener();
    private static final ConcurrentHashMap<Long, RunningOperation> OPERATIONS = new ConcurrentHashMap<>();
    private static final ExecutorService timeoutProcessor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("ButtonOperations-TimeoutAction-Processor-%s");
        return t;
    });

    static {
        ScheduledExecutorService s = Executors.newScheduledThreadPool(10, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("ButtonOperations-Timeout-Processor");
            return t;
        });

        Metrics.THREAD_POOL_COLLECTOR.add("button-operations-timeout", s);
        s.scheduleAtFixedRate(() -> OPERATIONS.values().removeIf(op -> op.isTimedOut(true)), 1, 1, TimeUnit.SECONDS);
    }

    public static Future<Void> get(Long messageId) {
        RunningOperation o = OPERATIONS.get(messageId);
        return o == null ? null : o.future;
    }

    public static Future<Void> create(Message message, long timeoutSeconds, ButtonOperation operation, Button... defaultButtons) {
        if (!message.getAuthor().equals(message.getJDA().getSelfUser())) {
            throw new IllegalArgumentException("Must provide a message sent by the bot");
        }

        // TODO: i18n
        if (!message.getChannel().canTalk()) {
            message.editMessage("The bot needs View Channel and Message Write on this channel (or Send Messages in Threads if in a thread) to display buttons.").queue();
            return null;
        }

        Future<Void> f = create(message.getIdLong(), timeoutSeconds, operation);
        if (f == null) {
            return null;
        }

        if (defaultButtons.length > 0) {
            message.editMessageComponents(ActionRow.of(defaultButtons)).queue();
        }

        return f;
    }

    public static Future<Void> create(Message message, long timeoutSeconds, ButtonOperation operation, Collection<Button> defaultButtons) {
        if (!message.getAuthor().equals(message.getJDA().getSelfUser())) {
            throw new IllegalArgumentException("Must provide a message sent by the bot");
        }

        if (!message.getChannel().canTalk()) {
            message.editMessage("The bot needs View Channel and Message Write on this channel (or Send Messages in Threads if in a thread) to display buttons.").queue();
            return null;
        }

        Future<Void> f = create(message.getIdLong(), timeoutSeconds, operation);
        if (f == null) {
            return null;
        }

        if (!defaultButtons.isEmpty()) {
            message.editMessageComponents(ActionRow.of(defaultButtons)).queue();
        }

        return f;
    }

    public static Future<Void> createRows(Message message, long timeoutSeconds, ButtonOperation operation, Collection<ActionRow> defaultButtons) {
        if (!message.getAuthor().equals(message.getJDA().getSelfUser())) {
            throw new IllegalArgumentException("Must provide a message sent by the bot");
        }

        if (!message.getChannel().canTalk()) {
            message.editMessage("The bot needs View Channel and Message Write on this channel (or Send Messages in Threads if in a thread) to display buttons.").queue();
            return null;
        }

        Future<Void> f = create(message.getIdLong(), timeoutSeconds, operation);
        if (f == null) {
            return null;
        }

        if (!defaultButtons.isEmpty()) {
            message.editMessageComponents(defaultButtons).queue();
        }

        return f;
    }

    public static Future<Void> create(long messageId, long timeoutSeconds, ButtonOperation operation) {
        if (timeoutSeconds < 1)
            throw new IllegalArgumentException("Timeout is less than 1 second");

        if (operation == null)
            throw new IllegalArgumentException("Operation cannot be null!");

        RunningOperation o = OPERATIONS.get(messageId);

        // Already running?
        if (o != null) {
            return null;
        }

        o = new RunningOperation(operation, new OperationFuture(messageId), TimeUnit.SECONDS.toNanos(timeoutSeconds));
        OPERATIONS.put(messageId, o);

        return o.future;
    }

    public static class ButtonListener implements EventListener {
        @Override
        public void onEvent(@Nonnull GenericEvent e) {
            if (e instanceof ButtonInteractionEvent evt) {
                var guild = evt.getGuild();
                var member = evt.getMember();

                if (guild == null || member == null) {
                    return;
                }

                if (member.getIdLong() == guild.getSelfMember().getIdLong()) {
                    return;
                }

                var messageId = evt.getMessageIdLong();
                ButtonOperations.RunningOperation o = OPERATIONS.get(messageId);
                if (o == null) {
                    return;
                }

                // Forward this event to the anonymous class.
                evt.deferEdit().queue();
                int i = o.operation.click(evt);
                if (i == Operation.COMPLETED) {
                    //Operation has been completed. We can remove this from the running operations list and go on.
                    OPERATIONS.remove(messageId);
                    o.future.complete(null);
                }
            }
        }
    }

    public static EventListener listener() {
        return LISTENER;
    }

    private static class RunningOperation {
        private final ButtonOperation operation;
        private final OperationFuture future;
        private final long timeout;
        private boolean expired;

        private RunningOperation(ButtonOperation operation, OperationFuture future, long timeout) {
            this.expired = false;
            this.operation = operation;
            this.future = future;
            this.timeout = System.nanoTime() + timeout;
        }

        boolean isTimedOut(boolean expire) {
            if (expired) {
                return true;
            }

            boolean out = timeout - System.nanoTime() < 0;
            if (out && expire) {
                try {
                    timeoutProcessor.submit(() -> {
                        try {
                            operation.onExpire();
                        } catch (Exception ignored) {}
                    });
                } catch (Exception e) {
                    e.printStackTrace(); // what?
                }

                expired = true;
            }

            return out;
        }
    }

    private static class OperationFuture extends CompletableFuture<Void> {
        private final long id;

        OperationFuture(long id) {
            this.id = id;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            super.cancel(mayInterruptIfRunning);
            ButtonOperations.RunningOperation o = OPERATIONS.remove(id);

            if (o == null) {
                return false;
            }

            o.operation.onCancel();
            return true;
        }
    }
}
