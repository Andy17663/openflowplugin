/**
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowplugin.api.openflow.md.core.sal;

import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.transaction.rev131103.TransactionId;
import org.opendaylight.yangtools.yang.binding.Notification;

/**
 * @param <N> type of notification
 */
public interface NotificationComposer<N extends Notification> {
    
    /**
     * @param xid corresponding OF transaction id
     * @return notification instance
     */
    N compose(TransactionId xid);
}
