package simulator;

import fr.emse.fayol.maqit.simulator.components.Message;

/**
 * A structured message format for robot coordination.
 * Extends the basic Message class with specific message types and structured
 * data.
 */
public class CoordinationMessage extends Message {

    public enum MessageType {
        TASK_ANNOUNCEMENT, // Announce a package that needs delivery
        TASK_BID, // Bid on a task
        TASK_ASSIGNMENT, // Assign a task to a robot
        ZONE_STATUS, // Report status of a zone (start, transit, charging)
        POSITION_UPDATE, // Share current position and destination
        HANDOFF_REQUEST, // Request package handoff
        HANDOFF_ACCEPT, // Accept package handoff
        HANDOFF_REJECT, // Reject package handoff
        PATH_CONFLICT, // Alert about potential path conflict
        BATTERY_STATUS // Share battery status
    }

    private MessageType type;
    private String data; // JSON-formatted data specific to the message type
    private int priority; // Higher number = higher priority
    private long timestamp; // When the message was created

    public CoordinationMessage(int emitter, MessageType type, String data, int priority) {
        super(emitter, type.toString() + ":" + data);
        this.type = type;
        this.data = data;
        this.priority = priority;
        this.timestamp = System.currentTimeMillis();
    }

    public MessageType getType() {
        return type;
    }

    public String getData() {
        return data;
    }

    public int getPriority() {
        return priority;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Parse a coordination message from a standard message
     */
    public static CoordinationMessage fromMessage(Message msg) {
        String content = msg.getContent();
        String[] parts = content.split(":", 2);

        if (parts.length != 2) {
            return null; // Not a valid coordination message
        }

        try {
            MessageType type = MessageType.valueOf(parts[0]);
            return new CoordinationMessage(msg.getEmitter(), type, parts[1], 0);
        } catch (IllegalArgumentException e) {
            return null; // Not a valid message type
        }
    }

    /**
     * Create a task announcement message
     */
    public static CoordinationMessage createTaskAnnouncement(int emitter, int packageId, String startZone,
            int destinationId, double urgency) {
        String data = String.format("{\"packageId\":%d,\"startZone\":\"%s\",\"destinationId\":%d,\"urgency\":%.2f}",
                packageId, startZone, destinationId, urgency);
        return new CoordinationMessage(emitter, MessageType.TASK_ANNOUNCEMENT, data, (int) (urgency * 10));
    }

    /**
     * Create a task bid message
     */
    public static CoordinationMessage createTaskBid(int emitter, int packageId, double fitness, double batteryLevel,
            double distanceToPackage) {
        String data = String.format(
                "{\"packageId\":%d,\"fitness\":%.2f,\"batteryLevel\":%.2f,\"distanceToPackage\":%.2f}",
                packageId, fitness, batteryLevel, distanceToPackage);
        return new CoordinationMessage(emitter, MessageType.TASK_BID, data, (int) (fitness * 10));
    }

    /**
     * Create a position update message
     */
    public static CoordinationMessage createPositionUpdate(int emitter, int x, int y, int destX, int destY,
            boolean hasPackage) {
        String data = String.format("{\"x\":%d,\"y\":%d,\"destX\":%d,\"destY\":%d,\"hasPackage\":%b}",
                x, y, destX, destY, hasPackage);
        return new CoordinationMessage(emitter, MessageType.POSITION_UPDATE, data, 1);
    }

    /**
     * Create a handoff request message
     */
    public static CoordinationMessage createHandoffRequest(int emitter, int packageId, int x, int y, int destinationId,
            double batteryLevel) {
        String data = String.format("{\"packageId\":%d,\"x\":%d,\"y\":%d,\"destinationId\":%d,\"batteryLevel\":%.2f}",
                packageId, x, y, destinationId, batteryLevel);
        return new CoordinationMessage(emitter, MessageType.HANDOFF_REQUEST, data, 5);
    }

    /**
     * Create a battery status message
     */
    public static CoordinationMessage createBatteryStatus(int emitter, double batteryLevel, double batteryCapacity,
            boolean isRecharging) {
        String data = String.format("{\"batteryLevel\":%.2f,\"batteryCapacity\":%.2f,\"isRecharging\":%b}",
                batteryLevel, batteryCapacity, isRecharging);
        return new CoordinationMessage(emitter, MessageType.BATTERY_STATUS, data, 2);
    }

    /**
     * Create a zone status message
     */
    public static CoordinationMessage createZoneStatus(int emitter, String zoneType, String zoneId, int x, int y,
            int packageCount, int capacity) {
        String data = String.format(
                "{\"zoneType\":\"%s\",\"zoneId\":\"%s\",\"x\":%d,\"y\":%d,\"packageCount\":%d,\"capacity\":%d}",
                zoneType, zoneId, x, y, packageCount, capacity);
        return new CoordinationMessage(emitter, MessageType.ZONE_STATUS, data, 3);
    }

    /**
     * Create a path conflict message
     */
    public static CoordinationMessage createPathConflict(int emitter, int x, int y) {
        String data = String.format("{\"x\":%d,\"y\":%d}", x, y);
        return new CoordinationMessage(emitter, MessageType.PATH_CONFLICT, data, 5);
    }
}
