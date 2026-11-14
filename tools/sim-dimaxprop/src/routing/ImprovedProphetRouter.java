package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid Epidemic-PRoPHET Router for Delay Tolerant Networks.
 *
 * The protocol works in two phases:
 *
 * Phase 1: Limited Epidemic
 * -------------------------
 * A message can be forwarded while:
 *      n_m < maxForwards
 * AND
 *      hopCount < maxHops
 *
 * Phase 2: PRoPHET
 * ----------------
 * Once the Epidemic conditions are no longer satisfied,
 * the message is forwarded only when the encountered peer
 * has a higher delivery predictability for the destination.
 *
 * PRoPHET functionality:
 * 1. Encounter update
 * 2. Transitivity update
 * 3. Aging of delivery predictabilities
 */
public class ImprovedProphetRouter extends ActiveRouter {

    /* =========================================================
     * Configuration settings
     * ========================================================= */

    /** Maximum number of Epidemic forwards */
    public static final String N_M_SETTING = "maxForwards";

    /** Maximum number of hops during Epidemic phase */
    public static final String H_M_SETTING = "maxHops";

    /** Initial PRoPHET encounter probability */
    public static final String P_INIT_SETTING = "pInit";

    /** PRoPHET aging factor */
    public static final String GAMMA_SETTING = "gamma";

    /** PRoPHET transitivity factor */
    public static final String BETA_SETTING = "beta";

    /** Number of simulated seconds per PRoPHET time unit */
    public static final String SECONDS_IN_UNIT_SETTING =
            "secondsInTimeUnit";

    /** Message property storing number of Epidemic forwards */
    public static final String FORWARD_COUNT = "n_m";


    /* =========================================================
     * Protocol parameters
     * ========================================================= */

    private int maxForwards;
    private int maxHops;

    private double pInit;
    private double gamma;
    private double beta;

    private double secondsInTimeUnit;


    /* =========================================================
     * PRoPHET state
     *
     * preds stores:
     *
     *     Destination node -> delivery predictability
     *
     * Example:
     *
     *     Node A knows:
     *
     *     P(A,B) = 0.75
     *     P(A,C) = 0.42
     * ========================================================= */

    private Map<DTNHost, Double> preds;

    /** Last simulation time at which aging was performed */
    private double lastAgeUpdate;


    /* =========================================================
     * Constructor
     * ========================================================= */

    public ImprovedProphetRouter(Settings s) {
        super(s);

        maxForwards = s.getInt(N_M_SETTING);
        maxHops = s.getInt(H_M_SETTING);

        pInit = s.getDouble(P_INIT_SETTING);
        gamma = s.getDouble(GAMMA_SETTING);
        beta = s.getDouble(BETA_SETTING);

        /*
         * If secondsInTimeUnit is not specified,
         * use 1 second as one PRoPHET time unit.
         */
        if (s.contains(SECONDS_IN_UNIT_SETTING)) {
            secondsInTimeUnit =
                    s.getDouble(SECONDS_IN_UNIT_SETTING);
        } else {
            secondsInTimeUnit = 1.0;
        }

        preds = new HashMap<>();

        /*
         * Initialize the aging clock.
         */
        lastAgeUpdate = SimClock.getTime();
    }


    /* =========================================================
     * Copy constructor
     *
     * The ONE uses this when creating router instances
     * for different hosts.
     * ========================================================= */

    public ImprovedProphetRouter(ImprovedProphetRouter r) {
        super(r);

        this.maxForwards = r.maxForwards;
        this.maxHops = r.maxHops;

        this.pInit = r.pInit;
        this.gamma = r.gamma;
        this.beta = r.beta;

        this.secondsInTimeUnit =
                r.secondsInTimeUnit;

        /*
         * Every node must maintain its own prediction table.
         */
        this.preds = new HashMap<>();

        this.lastAgeUpdate =
                SimClock.getTime();
    }


    /* =========================================================
     * Router replication
     * ========================================================= */

    @Override
    public MessageRouter replicate() {
        return new ImprovedProphetRouter(this);
    }


    /* =========================================================
     * Message creation
     *
     * Every new message starts with:
     *
     *      n_m = 0
     *
     * n_m = number of Epidemic forwards.
     * ========================================================= */

    @Override
    public boolean createNewMessage(Message m) {

        m.addProperty(
                FORWARD_COUNT,
                Integer.valueOf(0)
        );

        return super.createNewMessage(m);
    }


    /* =========================================================
     * Main router update
     *
     * Called repeatedly by The ONE during simulation.
     *
     * Order:
     *
     * 1. Execute base ActiveRouter update
     * 2. Age PRoPHET predictions
     * 3. Check if a transfer is already running
     * 4. Try direct delivery
     * 5. Execute our hybrid routing algorithm
     * ========================================================= */

    @Override
    public void update() {

        super.update();

        /*
         * Update PRoPHET aging.
         */
        agePredictabilities();

        /*
         * If this node is already transferring a message,
         * don't start another transfer.
         */
        if (isTransferring()) {
            return;
        }

        /*
         * Direct delivery has priority.
         *
         * If a connected node is the destination,
         * deliver the message directly.
         */
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        /*
         * No direct delivery was possible.
         *
         * Execute our hybrid routing algorithm.
         */
        tryOtherMessages();
    }


    /* =========================================================
     * Hybrid Epidemic + PRoPHET routing
     * ========================================================= */

    private void tryOtherMessages() {

        List<Connection> connections =
                getConnections();

        /*
         * No connected nodes.
         */
        if (connections.isEmpty()) {
            return;
        }


        /*
         * Make a copy of the message collection.
         *
         * This prevents problems if the message list changes
         * while a transfer is being initiated.
         */
        List<Message> messages =
                new ArrayList<>(
                        getMessageCollection()
                );


        /*
         * Check every connected peer.
         */
        for (Connection con : connections) {

            DTNHost peer =
                    con.getOtherNode(getHost());


            /*
             * Only communicate with nodes that are
             * running our protocol.
             */
            if (!(peer.getRouter()
                    instanceof ImprovedProphetRouter)) {

                continue;
            }


            ImprovedProphetRouter peerRouter =
                    (ImprovedProphetRouter)
                            peer.getRouter();


            /*
             * Check every message stored at this node.
             */
            for (Message m : messages) {

                /*
                 * Don't send a message that the peer
                 * already possesses.
                 */
                if (peer.getRouter()
                        .hasMessage(m.getId())) {

                    continue;
                }


                /* -----------------------------------------
                 * Read message state
                 * ----------------------------------------- */

                int nm =
                        getForwardCount(m);

                int hm =
                        m.getHopCount();

                DTNHost destination =
                        m.getTo();


                /* -----------------------------------------
                 * PHASE 1: LIMITED EPIDEMIC
                 * ----------------------------------------- */

                /*
                 * Example:
                 *
                 * maxForwards = 5
                 * maxHops = 3
                 *
                 * Epidemic forwarding is allowed only when:
                 *
                 * n_m < 5
                 * AND
                 * hopCount < 3
                 */
                boolean isEpidemic =
                        (nm < maxForwards)
                        &&
                        (hm < maxHops);


                /* -----------------------------------------
                 * PHASE 2: PRoPHET
                 * ----------------------------------------- */

                /*
                 * Our predictability for destination.
                 */
                double myPred =
                        getPredFor(destination);


                /*
                 * Peer predictability for destination.
                 */
                double peerPred =
                        peerRouter.getPredFor(destination);


                /*
                 * PRoPHET forwarding happens only when
                 * the Epidemic phase is no longer active.
                 *
                 * Forward only if:
                 *
                 *      P(peer,destination)
                 *          >
                 *      P(this node,destination)
                 */
                boolean isProphet =
                        !isEpidemic
                        &&
                        (peerPred > myPred);


                /* -----------------------------------------
                 * FINAL FORWARDING DECISION
                 * ----------------------------------------- */

                if (isEpidemic || isProphet) {

                    int result =
                            startTransfer(m, con);


                    /*
                     * Transfer successfully initiated.
                     *
                     * Stop checking other messages for now.
                     */
                    if (result == RCV_OK) {
                        return;
                    }
                }
            }
        }
    }


    /* =========================================================
     * Transfer completion
     *
     * Called after a message transfer finishes.
     *
     * n_m is increased only for an Epidemic transfer.
     * ========================================================= */

    @Override
    protected void transferDone(Connection con) {

        String msgId =
                con.getMessage().getId();


        Message myMsg =
                getMessage(msgId);


        if (myMsg != null) {

            int nm =
                    getForwardCount(myMsg);

            int hm =
                    myMsg.getHopCount();


            /*
             * If the message was still within the
             * Epidemic forwarding limits, count this
             * transfer as an Epidemic forward.
             */
            if (nm < maxForwards
                    &&
                    hm < maxHops) {

                myMsg.updateProperty(
                        FORWARD_COUNT,
                        Integer.valueOf(nm + 1)
                );
            }
        }


        /*
         * Let ActiveRouter perform its normal
         * transfer completion processing.
         */
        super.transferDone(con);
    }


    /* =========================================================
     * Connection event
     *
     * Called when a connection changes state.
     *
     * When a connection becomes active:
     *
     * 1. Encounter update
     * 2. Transitivity update
     * ========================================================= */

    @Override
    public void changedConnection(Connection con) {

        super.changedConnection(con);


        /*
         * We only update PRoPHET when the connection
         * becomes active.
         */
        if (!con.isUp()) {
            return;
        }


        DTNHost peer =
                con.getOtherNode(getHost());


        /*
         * Only exchange prediction information with
         * nodes using ImprovedProphetRouter.
         */
        if (!(peer.getRouter()
                instanceof ImprovedProphetRouter)) {

            return;
        }


        ImprovedProphetRouter peerRouter =
                (ImprovedProphetRouter)
                        peer.getRouter();


        /* ==============================================
         * 1. ENCOUNTER UPDATE
         *
         * P(A,B) =
         *
         * P(A,B)old
         * +
         * (1-P(A,B)old) * Pinit
         * ============================================== */

        double pOld =
                getPredFor(peer);


        double pNew =
                pOld
                +
                (1.0 - pOld) * pInit;


        preds.put(
                peer,
                pNew
        );


        /* ==============================================
         * 2. TRANSITIVITY UPDATE
         *
         * P(A,C) =
         *
         * P(A,C)old
         * +
         * (1-P(A,C)old)
         * * P(A,B)
         * * P(B,C)
         * * beta
         * ============================================== */

        Map<DTNHost, Double> peerPreds =
                peerRouter.getPreds();


        /*
         * Current node's probability of reaching peer.
         */
        double pAB =
                getPredFor(peer);


        /*
         * Examine everything the peer knows about.
         */
        for (Map.Entry<DTNHost, Double> entry
                : peerPreds.entrySet()) {

            DTNHost destination =
                    entry.getKey();

            double pBC =
                    entry.getValue();


            /*
             * Don't calculate:
             *
             * P(A,A)
             * P(A,B)
             */
            if (destination == getHost()
                    ||
                    destination == peer) {

                continue;
            }


            /*
             * Current P(A,C).
             */
            double pACOld =
                    getPredFor(destination);


            /*
             * New transitive P(A,C).
             */
            double pACNew =
                    pACOld
                    +
                    (1.0 - pACOld)
                    * pAB
                    * pBC
                    * beta;


            /*
             * Keep the higher predictability.
             */
            preds.put(
                    destination,
                    Math.max(
                            pACOld,
                            pACNew
                    )
            );
        }
    }


    /* =========================================================
     * PRoPHET Aging
     *
     * P = P * gamma^k
     *
     * where:
     *
     * k =
     * elapsed simulation time /
     * secondsInTimeUnit
     * ========================================================= */

    private void agePredictabilities() {

        double currentTime =
                SimClock.getTime();


        double timeDiff =
                (currentTime - lastAgeUpdate)
                /
                secondsInTimeUnit;


        /*
         * No time has passed.
         */
        if (timeDiff <= 0) {
            return;
        }


        /*
         * Calculate aging factor.
         */
        double factor =
                Math.pow(
                        gamma,
                        timeDiff
                );


        /*
         * Age every stored prediction.
         */
        for (Map.Entry<DTNHost, Double> entry
                : preds.entrySet()) {

            entry.setValue(
                    entry.getValue() * factor
            );
        }


        /*
         * Remember the latest aging time.
         */
        lastAgeUpdate =
                currentTime;
    }


    /* =========================================================
     * Get the number of Epidemic forwards for a message.
     * ========================================================= */

    private int getForwardCount(Message m) {

        Object value =
                m.getProperty(FORWARD_COUNT);


        /*
         * Normal case.
         */
        if (value instanceof Integer) {

            return (Integer) value;
        }


        /*
         * Safety fallback:
         *
         * If n_m doesn't exist, initialize it to zero.
         */
        m.addProperty(
                FORWARD_COUNT,
                Integer.valueOf(0)
        );


        return 0;
    }


    /* =========================================================
     * Get delivery predictability for a destination.
     * ========================================================= */

    public double getPredFor(DTNHost host) {

        return preds.getOrDefault(
                host,
                0.0
        );
    }


    /* =========================================================
     * Get complete PRoPHET prediction table.
     * ========================================================= */

    public Map<DTNHost, Double> getPreds() {

        return preds;
    }
}