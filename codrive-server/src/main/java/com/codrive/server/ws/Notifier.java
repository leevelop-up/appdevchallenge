package com.codrive.server.ws;

import com.codrive.server.service.Events;
import com.codrive.server.service.SnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

@Component
@RequiredArgsConstructor
public class Notifier {
    private final ConnectionRegistry registry;
    private final SnapshotService snapshots;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVehicle(Events.ToVehicle e) {
        registry.sendVehicle(e.installationId(), e.type(), e.payload());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPhone(Events.ToPhone e) {
        registry.sendPhone(e.requestId(), e.type(), e.payload());
        if (e.closeAfter()) {
            registry.closePhone(e.requestId(), CloseStatus.NORMAL);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionChanged(Events.SessionChanged e) {
        SnapshotService.All all = snapshots.buildAll(e.sessionId());
        if (all == null) {
            return;
        }
        registry.sendVehicle(all.installationId(), "SESSION_SNAPSHOT", all.vehicle());
        all.phonesByRequestId().forEach((requestId, state) -> registry.sendPhone(requestId, "STATE_UPDATE", state));
    }
}
