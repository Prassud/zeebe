/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.state.message;

import io.camunda.zeebe.db.ColumnFamily;
import io.camunda.zeebe.db.TransactionContext;
import io.camunda.zeebe.db.ZeebeDb;
import io.camunda.zeebe.db.impl.DbCompositeKey;
import io.camunda.zeebe.db.impl.DbLong;
import io.camunda.zeebe.db.impl.DbNil;
import io.camunda.zeebe.db.impl.DbString;
import io.camunda.zeebe.engine.state.ZbColumnFamilies;
import io.camunda.zeebe.engine.state.mutable.MutableProcessMessageSubscriptionState;
import io.camunda.zeebe.engine.state.mutable.MutableTransientProcessMessageSubscriptionState;
import io.camunda.zeebe.protocol.impl.record.value.message.ProcessMessageSubscriptionRecord;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;

public final class DbProcessMessageSubscriptionState
    implements MutableProcessMessageSubscriptionState,
        MutableTransientProcessMessageSubscriptionState {

  // (elementInstanceKey, messageName) => ProcessMessageSubscription
  private final DbLong elementInstanceKey;
  private final DbString messageName;
  private final DbCompositeKey<DbLong, DbString> elementKeyAndMessageName;
  private final ProcessMessageSubscription processMessageSubscription;
  private final ColumnFamily<DbCompositeKey<DbLong, DbString>, ProcessMessageSubscription>
      subscriptionColumnFamily;

  // (sentTime, elementInstanceKey, messageName) => \0
  private final DbLong sentTime;
  private final DbCompositeKey<DbLong, DbCompositeKey<DbLong, DbString>> sentTimeCompositeKey;
  private final ColumnFamily<DbCompositeKey<DbLong, DbCompositeKey<DbLong, DbString>>, DbNil>
      sentTimeColumnFamily;

  private final TransientProcessMessageSubscriptionState transientState =
      new TransientProcessMessageSubscriptionState(this);

  public DbProcessMessageSubscriptionState(
      final ZeebeDb<ZbColumnFamilies> zeebeDb, final TransactionContext transactionContext) {
    elementInstanceKey = new DbLong();
    messageName = new DbString();
    elementKeyAndMessageName = new DbCompositeKey<>(elementInstanceKey, messageName);
    processMessageSubscription = new ProcessMessageSubscription();

    subscriptionColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.PROCESS_SUBSCRIPTION_BY_KEY,
            transactionContext,
            elementKeyAndMessageName,
            processMessageSubscription);

    sentTime = new DbLong();
    sentTimeCompositeKey = new DbCompositeKey<>(sentTime, elementKeyAndMessageName);
    sentTimeColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.PROCESS_SUBSCRIPTION_BY_SENT_TIME,
            transactionContext,
            sentTimeCompositeKey,
            DbNil.INSTANCE);

    subscriptionColumnFamily.whileTrue(
        (compositeKey, subscription) -> {
          if (subscription.isOpening() || subscription.isClosing()) {
            transientState.add(subscription.getRecord());
          }
          return true;
        });
  }

  @Override
  public void put(
      final long key, final ProcessMessageSubscriptionRecord record, final long commandSentTime) {
    wrapSubscriptionKeys(record.getElementInstanceKey(), record.getMessageNameBuffer());

    processMessageSubscription.reset();
    processMessageSubscription.setKey(key).setRecord(record);

    subscriptionColumnFamily
        .put(elementKeyAndMessageName, processMessageSubscription);

    sentTime.wrapLong(commandSentTime);
    sentTimeColumnFamily.put(sentTimeCompositeKey, DbNil.INSTANCE);

    transientState.add(record, commandSentTime);
  }

  @Override
  public void updateToOpenedState(final ProcessMessageSubscriptionRecord record) {
    update(record, s -> s.setRecord(record).setOpened());
    transientState.remove(record);
  }

  @Override
  public void updateToClosingState(
      final ProcessMessageSubscriptionRecord record, final long commandSentTime) {
    update(record, s -> s.setRecord(record).setClosing());
    transientState.add(record, commandSentTime);
  }

  @Override
  public boolean remove(final long elementInstanceKey, final DirectBuffer messageName) {
    final ProcessMessageSubscription subscription =
        getSubscription(elementInstanceKey, messageName);
    final boolean found = subscription != null;
    if (found) {
      remove(subscription);
    }
    return found;
  }

  @Override
  public ProcessMessageSubscription getSubscription(
      final long elementInstanceKey, final DirectBuffer messageName) {
    wrapSubscriptionKeys(elementInstanceKey, messageName);

    return subscriptionColumnFamily.get(elementKeyAndMessageName);
  }

  @Override
  public void visitElementSubscriptions(
      final long elementInstanceKey, final ProcessMessageSubscriptionVisitor visitor) {
    this.elementInstanceKey.wrapLong(elementInstanceKey);

    subscriptionColumnFamily.whileEqualPrefix(
        this.elementInstanceKey,
        (compositeKey, subscription) -> {
          visitor.visit(subscription);
        });
  }

  @Override
  public boolean existSubscriptionForElementInstance(
      final long elementInstanceKey, final DirectBuffer messageName) {
    wrapSubscriptionKeys(elementInstanceKey, messageName);

    return subscriptionColumnFamily.exists(elementKeyAndMessageName);
  }

  @Override
  public void visitSubscriptionBefore(
      final long deadline, final ProcessMessageSubscriptionVisitor visitor) {

    transientState.visitSubscriptionBefore(deadline, visitor);
  }

  @Override
  public void updateSentTime(
      final ProcessMessageSubscription subscription, final long commandSentTime) {
    transientState.updateSentTime(subscription, commandSentTime);
  }

  private void update(
      final ProcessMessageSubscriptionRecord record,
      final Consumer<ProcessMessageSubscription> modifier) {
    final ProcessMessageSubscription subscription =
        getSubscription(record.getElementInstanceKey(), record.getMessageNameBuffer());
    if (subscription == null) {
      return;
    }

    update(subscription, modifier);
  }

  private void update(
      final ProcessMessageSubscription subscription,
      final Consumer<ProcessMessageSubscription> modifier) {
    final long previousSentTime = subscription.getCommandSentTime();
    modifier.accept(subscription);

    wrapSubscriptionKeys(
        subscription.getRecord().getElementInstanceKey(),
        subscription.getRecord().getMessageNameBuffer());
    subscriptionColumnFamily.put(elementKeyAndMessageName, subscription);

    final long updatedSentTime = subscription.getCommandSentTime();
    if (updatedSentTime != previousSentTime) {
      if (previousSentTime > 0) {
        sentTime.wrapLong(previousSentTime);
        sentTimeColumnFamily.delete(sentTimeCompositeKey);
      }

      if (updatedSentTime > 0) {
        sentTime.wrapLong(updatedSentTime);
        sentTimeColumnFamily.put(sentTimeCompositeKey, DbNil.INSTANCE);
      }
    }
  }

  private void remove(final ProcessMessageSubscription subscription) {
    wrapSubscriptionKeys(
        subscription.getRecord().getElementInstanceKey(),
        subscription.getRecord().getMessageNameBuffer());

    subscriptionColumnFamily.delete(elementKeyAndMessageName);

    sentTime.wrapLong(subscription.getCommandSentTime());
    sentTimeColumnFamily.delete(sentTimeCompositeKey);
    transientState.remove(subscription.getRecord());
  }

  private void wrapSubscriptionKeys(final long elementInstanceKey, final DirectBuffer messageName) {
    this.elementInstanceKey.wrapLong(elementInstanceKey);
    this.messageName.wrapBuffer(messageName);
  }
}
