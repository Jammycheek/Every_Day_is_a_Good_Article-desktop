import poc.*;

// Manual synthetic drivers only. Constructs a mock reply; does not authenticate any phone.
class MockGrantFixture {
    static void accept(RecoveryController pc, String device, String session, long revision,
                       long localDeadline, String instance, long sentAt) {
        long duration = localDeadline - sentAt;
        PendingGrantRequest request = pc.beginGrantRequest(new DatasetIdentity(device, instance),
            sentAt, duration, 0, false, false);
        pc.acceptGrant(new Grant(device, session, revision, duration, instance,
            request.getRequestNonce(), request.getMinimumEpoch()), request, sentAt);
    }
}
