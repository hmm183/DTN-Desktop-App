package report;

import java.util.List;

import core.DTNHost;
import core.Message;
import core.MessageListener;

/**
 * Tracks only M1.
 *
 * Shows:
 * 1. When M1 is created
 * 2. When M1 starts a transfer
 * 3. When M1 successfully transfers
 * 4. Hop count after every successful transfer
 * 5. Complete path
 * 6. When M1 reaches its destination
 */
public class SingleMessagePathReport extends Report
        implements MessageListener {

    private static final String TARGET_MESSAGE = "M1";

    private boolean delivered;

    public SingleMessagePathReport() {
        init();
    }

    @Override
    protected void init() {
        super.init();

        delivered = false;

        write("==================================================");
        write("        SINGLE MESSAGE PATH REPORT");
        write("==================================================");
        write("Tracking message: " + TARGET_MESSAGE);
        write("");
    }

    /*
     * M1 creation
     */
    @Override
    public void newMessage(Message m) {

        if (!TARGET_MESSAGE.equals(m.getId())) {
            return;
        }

        write("--------------------------------------------------");
        write("[" + format(getSimTime()) + "] MESSAGE CREATED");
        write("Message     : " + m.getId());
        write("Source      : " + m.getFrom());
        write("Destination : " + m.getTo());
        write("Hop count   : 0");
        write("Path        : " + buildPath(m));
        write("--------------------------------------------------");
    }

    /*
     * Transfer has started but is not completed yet.
     */
    @Override
    public void messageTransferStarted(
            Message m,
            DTNHost from,
            DTNHost to) {

        if (!TARGET_MESSAGE.equals(m.getId())) {
            return;
        }

        write(
            "[" + format(getSimTime()) + "] " +
            "TRANSFER STARTED | " +
            m.getId() + " | " +
            from + " -> " + to
        );
    }

    /*
     * Transfer failed/was interrupted.
     *
     * This is NOT counted as a hop.
     */
    @Override
    public void messageTransferAborted(
            Message m,
            DTNHost from,
            DTNHost to) {

        if (!TARGET_MESSAGE.equals(m.getId())) {
            return;
        }

        write(
            "[" + format(getSimTime()) + "] " +
            "TRANSFER ABORTED | " +
            m.getId() + " | " +
            from + " -> " + to +
            " | Hop count unchanged"
        );
    }

    /*
     * Successful transfer.
     *
     * The ONE has already updated the message path,
     * so getHopCount() gives the hop count of this
     * particular message copy.
     */
    @Override
    public void messageTransferred(
            Message m,
            DTNHost from,
            DTNHost to,
            boolean firstDelivery) {

        if (!TARGET_MESSAGE.equals(m.getId())) {
            return;
        }

        int hops = m.getHopCount();

        write("");
        write(
            "[" + format(getSimTime()) + "] " +
            "MESSAGE TRANSFERRED"
        );

        write("Message     : " + m.getId());
        write("Transfer    : " + from + " -> " + to);
        write("Hop number  : " + hops);
        write("Path so far : " + buildPath(m));

        /*
         * Destination reached.
         */
        if (firstDelivery) {

            delivered = true;

            write("");
            write("**************************************************");
            write("              DESTINATION REACHED");
            write("**************************************************");
            write("Message     : " + m.getId());
            write("Source      : " + m.getFrom());
            write("Destination : " + m.getTo());
            write("Final path  : " + buildPath(m));
            write("Total hops  : " + m.getHopCount());
            write("Delivery time: " + format(getSimTime()));
            write("**************************************************");
            write("");
        }
    }

    /*
     * Message dropped/removed.
     */
    @Override
    public void messageDeleted(
            Message m,
            DTNHost where,
            boolean dropped) {

        if (!TARGET_MESSAGE.equals(m.getId())) {
            return;
        }

        if (dropped) {
            write(
                "[" + format(getSimTime()) + "] " +
                "MESSAGE DROPPED | " +
                m.getId() + " at " + where
            );
        }
    }

    /*
     * Build:
     *
     * p0 -> p2 -> c4 -> c5
     */
    private String buildPath(Message m) {

        List<DTNHost> path = m.getHops();

        if (path == null || path.isEmpty()) {
            return "EMPTY";
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < path.size(); i++) {

            if (i > 0) {
                result.append(" -> ");
            }

            result.append(path.get(i));
        }

        return result.toString();
    }

    @Override
    public void done() {

        write("");
        write("==================================================");
        write("                 FINAL RESULT");
        write("==================================================");

        if (delivered) {
            write("M1 successfully reached destination.");
        }
        else {
            write("M1 did NOT reach destination.");
        }

        write("==================================================");

        super.done();
    }
}