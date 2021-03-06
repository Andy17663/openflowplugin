/**
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowplugin.openflow.md.core.sal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

import org.opendaylight.controller.sal.binding.api.NotificationProviderService;
import org.opendaylight.openflowplugin.api.OFConstants;
import org.opendaylight.openflowplugin.api.openflow.md.core.SwitchConnectionDistinguisher;
import org.opendaylight.openflowplugin.api.openflow.md.core.sal.NotificationComposer;
import org.opendaylight.openflowplugin.api.statistics.MessageSpy;
import org.opendaylight.openflowplugin.openflow.md.core.MessageFactory;
import org.opendaylight.openflowplugin.openflow.md.core.session.IMessageDispatchService;
import org.opendaylight.openflowplugin.openflow.md.core.session.SessionContext;
import org.opendaylight.openflowplugin.openflow.md.util.RpcInputOutputTuple;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.transaction.rev131103.TransactionAware;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.BarrierInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.BarrierOutput;
import org.opendaylight.yangtools.yang.binding.DataContainer;
import org.opendaylight.yangtools.yang.binding.Notification;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 *
 */
public abstract class OFRpcTaskUtil {
    protected static final Logger LOG = LoggerFactory.getLogger(OFRpcTaskUtil.class);
    /**
     * @param taskContext
     * @param isBarrier
     * @param cookie
     * @return rpcResult of given type, containing wrapped errors of barrier sending (if any) or success
     */
    public static Collection<RpcError> manageBarrier(OFRpcTaskContext taskContext, Boolean isBarrier,
            SwitchConnectionDistinguisher cookie) {
        Collection<RpcError> errors = null;
        if (Objects.firstNonNull(isBarrier, Boolean.FALSE)) {
            RpcInputOutputTuple<BarrierInput, ListenableFuture<RpcResult<BarrierOutput>>> sendBarrierRpc =
                    sendBarrier(taskContext.getSession(), cookie, taskContext.getMessageService());
            Future<RpcResult<BarrierOutput>> barrierFuture = sendBarrierRpc.getOutput();
            try {
                RpcResult<BarrierOutput> barrierResult = barrierFuture.get(
                        taskContext.getMaxTimeout(), taskContext.getMaxTimeoutUnit());
                if (!barrierResult.isSuccessful()) {
                    errors = barrierResult.getErrors();
                }
            } catch (Exception e) {
                RpcError rpcError = RpcResultBuilder.newWarning(
                        ErrorType.RPC,
                        OFConstants.ERROR_TAG_TIMEOUT,
                        "barrier sending failed",
                        OFConstants.APPLICATION_TAG,
                        "switch failed to respond on barrier request - message ordering is not preserved",
                        e);
                errors = Lists.newArrayList(rpcError);
            }
        }

        if (errors == null) {
            errors = Collections.emptyList();
        }

        return errors;
    }

    /**
     * @param session
     * @param cookie
     * @param messageService
     * @return barrier response
     */
    protected static RpcInputOutputTuple<BarrierInput, ListenableFuture<RpcResult<BarrierOutput>>> sendBarrier(SessionContext session,
            SwitchConnectionDistinguisher cookie, IMessageDispatchService messageService) {
        BarrierInput barrierInput = MessageFactory.createBarrier(
                session.getFeatures().getVersion(), session.getNextXid());
        Future<RpcResult<BarrierOutput>> barrierResult = messageService.barrier(barrierInput, cookie);
        ListenableFuture<RpcResult<BarrierOutput>> output = JdkFutureAdapters.listenInPoolThread(barrierResult);

        return new RpcInputOutputTuple<>(barrierInput, output);
    }

    /**
     * @param result rpcResult with success = false, errors = given collection
     * @param barrierErrors
     */
    public static <T> void wrapBarrierErrors(SettableFuture<RpcResult<T>> result,
            Collection<RpcError> barrierErrors) {
        result.set(RpcResultBuilder.<T>failed().withRpcErrors(barrierErrors).build());
    }

    /**
     * @param task of rpc
     * @param originalResult
     * @param notificationProviderService
     * @param notificationComposer lazy notification composer
     */
    public static <R extends RpcResult<? extends TransactionAware>, N extends Notification, INPUT extends DataContainer>
    void hookFutureNotification(
            final OFRpcTask<INPUT, R> task,
            ListenableFuture<R> originalResult,
            final NotificationProviderService notificationProviderService,
            final NotificationComposer<N> notificationComposer) {
        Futures.addCallback(originalResult, new FutureCallback<R>() {
            @Override
            public void onSuccess(R result) {
                if(null == notificationProviderService) {
                    LOG.warn("onSuccess(): notificationServiceProvider is null, could not publish result {}",result);
                } else if (notificationComposer == null) {
                    LOG.warn("onSuccess(): notificationComposer is null, could not publish result {}",result);
                } else if(result == null) {
                    LOG.warn("onSuccess(): result is null, could not publish result {}",result);
                } else if (result.getResult() == null) {
                    LOG.warn("onSuccess(): result.getResult() is null, could not publish result {}",result);
                } else if (result.getResult().getTransactionId() == null) {
                    LOG.warn("onSuccess(): result.getResult().getTransactionId() is null, could not publish result {}",result);
                } else {
                    notificationProviderService.publish(notificationComposer.compose(result.getResult().getTransactionId()));
                    task.getTaskContext().getMessageSpy().spyMessage(
                            task.getInput(), MessageSpy.STATISTIC_GROUP.TO_SWITCH_SUBMITTED_SUCCESS);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                //TODO: good place to notify MD-SAL about errors
                task.getTaskContext().getMessageSpy().spyMessage(
                        task.getInput(), MessageSpy.STATISTIC_GROUP.TO_SWITCH_SUBMITTED_FAILURE);
            }
        });
    }

    /**
     * @param task of rpc
     * @param originalResult
     * @param notificationProviderService
     * @param notificationComposer lazy notification composer
     * @return chained result with barrier
     */
    public static <TX extends TransactionAware, INPUT extends DataContainer>
    ListenableFuture<RpcResult<TX>> chainFutureBarrier(
            final OFRpcTask<INPUT, RpcResult<TX>> task,
            final ListenableFuture<RpcResult<TX>> originalResult) {

        ListenableFuture<RpcResult<TX>> chainResult = originalResult;
        if (Objects.firstNonNull(task.isBarrier(), Boolean.FALSE)) {

            chainResult = Futures.transform(originalResult, new AsyncFunction<RpcResult<TX>, RpcResult<TX>>() {

                @Override
                public ListenableFuture<RpcResult<TX>> apply(final RpcResult<TX> input) throws Exception {
                    if (input.isSuccessful()) {
                        RpcInputOutputTuple<BarrierInput, ListenableFuture<RpcResult<BarrierOutput>>> sendBarrierRpc = sendBarrier(
                                task.getSession(), task.getCookie(), task.getMessageService());
                        ListenableFuture<RpcResult<TX>> barrierTxResult = Futures.transform(
                                sendBarrierRpc.getOutput(),
                                transformBarrierToTransactionAware(input, sendBarrierRpc.getInput()));
                        return barrierTxResult;
                    } else {
                        return Futures.immediateFuture(input);
                    }
                }

            });
        }

        return chainResult;
    }

    /**
     * @param originalInput
     * @return
     */
    protected static <TX extends TransactionAware> Function<RpcResult<BarrierOutput>, RpcResult<TX>> transformBarrierToTransactionAware(
            final RpcResult<TX> originalInput, final BarrierInput barrierInput) {
        return new Function<RpcResult<BarrierOutput>, RpcResult<TX>>() {

            @Override
            public RpcResult<TX> apply(final RpcResult<BarrierOutput> barrierResult) {
                RpcResultBuilder<TX> rpcBuilder = null;
                if (barrierResult.isSuccessful()) {
                    rpcBuilder = RpcResultBuilder.<TX>success();
                } else {
                    rpcBuilder = RpcResultBuilder.<TX>failed();
                    RpcError rpcError = RpcResultBuilder.newWarning(
                            ErrorType.RPC,
                            OFConstants.ERROR_TAG_TIMEOUT,
                            "barrier sending failed",
                            OFConstants.APPLICATION_TAG,
                            "switch failed to respond on barrier request, barrier.xid = "+barrierInput.getXid(),
                            null);
                    List<RpcError> chainedErrors = new ArrayList<>();
                    chainedErrors.add(rpcError);
                    chainedErrors.addAll(barrierResult.getErrors());
                    rpcBuilder.withRpcErrors(chainedErrors);
                }

                rpcBuilder.withResult(originalInput.getResult());

                return rpcBuilder.build();
            }

        };
    }
}
