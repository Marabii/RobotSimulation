package simulator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages task coordination between robots in a decentralized way.
 * Each robot has its own TaskCoordinator instance.
 */
public class TaskCoordinator {

    private MyRobot owner;
    private Map<Integer, TaskInfo> knownTasks = new ConcurrentHashMap<>();
    private Map<Integer, RobotInfo> knownRobots = new ConcurrentHashMap<>();
    private Map<String, ZoneInfo> knownZones = new ConcurrentHashMap<>();

    // Auction-related fields
    private Map<Integer, List<TaskBid>> activeBids = new ConcurrentHashMap<>();
    private Set<Integer> assignedTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Map<Integer, Long> auctionDeadlines = new ConcurrentHashMap<>();

    // Constants
    private static final long AUCTION_DURATION_MS = 500; // How long to collect bids
    private static final long ROBOT_INFO_EXPIRY_MS = 5000; // How long robot info is valid
    private static final long ZONE_INFO_EXPIRY_MS = 10000; // How long zone info is valid

    public TaskCoordinator(MyRobot owner) {
        this.owner = owner;
    }

    /**
     * Process an incoming coordination message
     */
    public void processMessage(CoordinationMessage msg) {
        switch (msg.getType()) {
            case TASK_ANNOUNCEMENT:
                handleTaskAnnouncement(msg);
                break;
            case TASK_BID:
                handleTaskBid(msg);
                break;
            case TASK_ASSIGNMENT:
                handleTaskAssignment(msg);
                break;
            case POSITION_UPDATE:
                handlePositionUpdate(msg);
                break;
            case ZONE_STATUS:
                handleZoneStatus(msg);
                break;
            case HANDOFF_REQUEST:
                handleHandoffRequest(msg);
                break;
            case HANDOFF_ACCEPT:
                handleHandoffAccept(msg);
                break;
            case BATTERY_STATUS:
                handleBatteryStatus(msg);
                break;
            case PATH_CONFLICT:
                handlePathConflict(msg);
                break;
            default:
                // Unknown message type
                break;
        }
    }

    /**
     * Announce a new task (package delivery)
     */
    public void announceTask(int packageId, String startZone, int destinationId, double urgency) {
        // Create a task announcement
        CoordinationMessage announcement = CoordinationMessage.createTaskAnnouncement(
                owner.getId(), packageId, startZone, destinationId, urgency);

        // Store the task locally
        TaskInfo task = new TaskInfo(packageId, startZone, destinationId, urgency, System.currentTimeMillis());
        knownTasks.put(packageId, task);

        // Start an auction for this task
        auctionDeadlines.put(packageId, System.currentTimeMillis() + AUCTION_DURATION_MS);
        activeBids.put(packageId, new ArrayList<>());

        // Broadcast the announcement
        owner.broadcastMessage(announcement.getContent());

        // Submit our own bid
        submitBid(packageId);
    }

    /**
     * Submit a bid for a task based on fitness
     */
    private void submitBid(int packageId) {
        TaskInfo task = knownTasks.get(packageId);
        if (task == null)
            return;

        // Calculate fitness for this task
        double fitness = calculateFitness(task);

        // Create and send bid
        CoordinationMessage bid = CoordinationMessage.createTaskBid(
                owner.getId(),
                packageId,
                fitness,
                owner.getBatteryLevel(),
                distanceToStartZone(task.startZone));

        owner.broadcastMessage(bid.getContent());
    }

    /**
     * Calculate fitness for a task (higher is better)
     * Improved to better distribute tasks among robots
     */
    private double calculateFitness(TaskInfo task) {
        // Base fitness components
        double distanceFitness = 1.0 / (1.0 + distanceToStartZone(task.startZone));
        double batteryFitness = owner.getBatteryLevel() / owner.getBatteryCapacity();
        double loadFitness = owner.isCarryingPackage() ? 0.3 : 1.0; // Stronger penalty if already carrying

        // Get robot ID and use it for task distribution
        int robotId = owner.getId();

        // Calculate distance to destination
        double destDistance = distanceToDestination(task.destinationId);
        double destinationFitness = 1.0 / (1.0 + destDistance);

        // Zone-based responsibility - robots prefer tasks in their zone
        // Divide grid into quadrants and assign robots to quadrants based on ID
        int rows = owner.getGridRows();
        int cols = owner.getGridColumns();

        // Determine which quadrant this task's start zone is in
        ZoneInfo zone = knownZones.get(task.startZone);
        double zoneFitness = 0.5; // Default value

        if (zone != null) {
            int zoneQuadrant = getQuadrant(zone.x, zone.y, rows, cols);
            int robotPreferredQuadrant = robotId % 4; // Assign robots to quadrants based on ID

            // Boost fitness if task is in robot's preferred quadrant
            if (zoneQuadrant == robotPreferredQuadrant) {
                zoneFitness = 1.0;
            }
        }

        // Check if we have enough battery to complete the task
        double estimatedTaskDistance = distanceToStartZone(task.startZone) + destDistance;
        double batteryNeeded = estimatedTaskDistance * 1.5; // Add safety margin

        if (owner.getBatteryLevel() < batteryNeeded) {
            batteryFitness *= 0.2; // Heavier penalty if not enough battery
        }

        // Check if other robots are already heading to this start zone
        int robotsHeadingToSameZone = 0;
        for (RobotInfo robot : knownRobots.values()) {
            if (robot.id != owner.getId() && !robot.hasPackage) {
                ZoneInfo robotDestZone = getZoneAt(robot.destX, robot.destY);
                if (robotDestZone != null && robotDestZone.id.equals(task.startZone)) {
                    robotsHeadingToSameZone++;
                }
            }
        }

        // Penalize if other robots are already heading to this zone
        double competitionFitness = 1.0 / (1.0 + robotsHeadingToSameZone);

        // Combine all factors with weights
        return (distanceFitness * 0.3) +
                (batteryFitness * 0.2) +
                (loadFitness * 0.15) +
                (destinationFitness * 0.1) +
                (zoneFitness * 0.15) +
                (competitionFitness * 0.1);
    }

    /**
     * Determine which quadrant a position is in
     * 0: top-left, 1: top-right, 2: bottom-left, 3: bottom-right
     */
    private int getQuadrant(int x, int y, int rows, int cols) {
        boolean isTop = y < rows / 2;
        boolean isLeft = x < cols / 2;

        if (isTop && isLeft)
            return 0;
        if (isTop && !isLeft)
            return 1;
        if (!isTop && isLeft)
            return 2;
        return 3;
    }

    /**
     * Find a zone at the given coordinates
     */
    private ZoneInfo getZoneAt(int x, int y) {
        for (ZoneInfo zone : knownZones.values()) {
            if (zone.x == x && zone.y == y) {
                return zone;
            }
        }
        return null;
    }

    /**
     * Calculate distance to a start zone
     */
    private double distanceToStartZone(String zoneId) {
        ZoneInfo zone = knownZones.get(zoneId);
        if (zone == null) {
            // If we don't know this zone, use a default high value
            return 100.0;
        }

        return owner.distanceTo(zone.x, zone.y);
    }

    /**
     * Calculate distance to a destination
     */
    private double distanceToDestination(int destinationId) {
        // Get destination coordinates from the robot's goals map
        int[] goalPos = owner.getGoalPosition(destinationId);
        if (goalPos == null) {
            return 100.0; // Default high value
        }

        return owner.distanceTo(goalPos[0], goalPos[1]);
    }

    /**
     * Process task bids and assign tasks
     * Called periodically by the robot
     */
    public void processAuctions() {
        long now = System.currentTimeMillis();

        // Check for expired auctions
        List<Integer> expiredAuctions = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : auctionDeadlines.entrySet()) {
            int packageId = entry.getKey();
            long deadline = entry.getValue();

            if (now > deadline) {
                expiredAuctions.add(packageId);
            }
        }

        // Process each expired auction
        for (int packageId : expiredAuctions) {
            List<TaskBid> bids = activeBids.get(packageId);
            if (bids != null && !bids.isEmpty()) {
                // Sort bids by fitness (highest first)
                bids.sort((a, b) -> Double.compare(b.fitness, a.fitness));

                // Get the winning bid
                TaskBid winner = bids.get(0);

                // If we're the auctioneer, announce the winner
                TaskInfo task = knownTasks.get(packageId);
                if (task != null && task.announcer == owner.getId()) {
                    // Create assignment message
                    String data = String.format("{\"packageId\":%d,\"robotId\":%d,\"fitness\":%.2f}",
                            packageId, winner.robotId, winner.fitness);
                    CoordinationMessage assignment = new CoordinationMessage(
                            owner.getId(), CoordinationMessage.MessageType.TASK_ASSIGNMENT, data, 8);

                    // Broadcast the assignment
                    owner.broadcastMessage(assignment.getContent());

                    // If we won, mark the task as assigned to us
                    if (winner.robotId == owner.getId()) {
                        assignedTasks.add(packageId);
                    }
                }
            }

            // Clean up this auction
            auctionDeadlines.remove(packageId);
            activeBids.remove(packageId);
        }
    }

    /**
     * Check if we should request a handoff for a package
     * Called periodically by the robot
     */
    public void considerHandoffs() {
        // Only consider handoffs if we're carrying a package
        if (!owner.isCarryingPackage())
            return;

        // Get our package info
        int packageId = owner.getCarriedPackageId();
        int destinationId = owner.getCarriedPackageDestination();

        // Calculate our fitness to complete this delivery
        double batteryLevel = owner.getBatteryLevel();
        double distanceToDestination = this.distanceToDestination(destinationId);
        double batteryNeeded = distanceToDestination * 1.5; // With safety margin

        // If we have enough battery, no need for handoff
        if (batteryLevel >= batteryNeeded)
            return;

        // Find nearby robots with better fitness
        List<RobotInfo> candidates = new ArrayList<>();
        for (RobotInfo robot : knownRobots.values()) {
            // Skip ourselves and robots carrying packages
            if (robot.id == owner.getId() || robot.hasPackage)
                continue;

            // Skip robots too far away
            if (owner.distanceTo(robot.x, robot.y) > 5)
                continue;

            // Skip robots with low battery
            if (robot.batteryLevel < batteryNeeded)
                continue;

            candidates.add(robot);
        }

        // If we found candidates, request a handoff from the best one
        if (!candidates.isEmpty()) {
            // Sort by battery level (highest first)
            candidates.sort((a, b) -> Double.compare(b.batteryLevel, a.batteryLevel));

            // Request handoff from the best candidate
            RobotInfo best = candidates.get(0);
            CoordinationMessage request = CoordinationMessage.createHandoffRequest(
                    owner.getId(), packageId, owner.getX(), owner.getY(), destinationId, batteryLevel);

            // Send directly to the target robot
            owner.sendDirectMessage(best.id, request.getContent());
        }
    }

    /**
     * Broadcast our current position and status
     * Called periodically by the robot
     */
    public void broadcastStatus() {
        // Broadcast position
        CoordinationMessage posUpdate = CoordinationMessage.createPositionUpdate(
                owner.getId(),
                owner.getX(),
                owner.getY(),
                owner.getDestX(),
                owner.getDestY(),
                owner.isCarryingPackage());

        owner.broadcastMessage(posUpdate.getContent());

        // Broadcast battery status
        CoordinationMessage batteryUpdate = CoordinationMessage.createBatteryStatus(
                owner.getId(),
                owner.getBatteryLevel(),
                owner.getBatteryCapacity(),
                owner.isRecharging());

        owner.broadcastMessage(batteryUpdate.getContent());
    }

    // Message handlers

    private void handleTaskAnnouncement(CoordinationMessage msg) {
        // Parse task data
        String data = msg.getData();
        // Simple parsing (in a real system, use a proper JSON parser)
        int packageId = Integer.parseInt(data.split("\"packageId\":")[1].split(",")[0]);
        String startZone = data.split("\"startZone\":\"")[1].split("\"")[0];
        int destinationId = Integer.parseInt(data.split("\"destinationId\":")[1].split(",")[0]);
        double urgency = Double.parseDouble(data.split("\"urgency\":")[1].split("}")[0]);

        // Store the task
        TaskInfo task = new TaskInfo(packageId, startZone, destinationId, urgency, System.currentTimeMillis());
        task.announcer = msg.getEmitter();
        knownTasks.put(packageId, task);

        // Submit a bid for this task
        submitBid(packageId);
    }

    private void handleTaskBid(CoordinationMessage msg) {
        // Parse bid data
        String data = msg.getData();
        int packageId = Integer.parseInt(data.split("\"packageId\":")[1].split(",")[0]);
        double fitness = Double.parseDouble(data.split("\"fitness\":")[1].split(",")[0]);
        double batteryLevel = Double.parseDouble(data.split("\"batteryLevel\":")[1].split(",")[0]);

        // Store the bid if we're tracking this auction
        List<TaskBid> bids = activeBids.get(packageId);
        if (bids != null) {
            TaskBid bid = new TaskBid(msg.getEmitter(), packageId, fitness, batteryLevel);
            bids.add(bid);
        }
    }

    private void handleTaskAssignment(CoordinationMessage msg) {
        // Parse assignment data
        String data = msg.getData();
        int packageId = Integer.parseInt(data.split("\"packageId\":")[1].split(",")[0]);
        int robotId = Integer.parseInt(data.split("\"robotId\":")[1].split(",")[0]);

        // If we won, mark the task as assigned to us
        if (robotId == owner.getId()) {
            assignedTasks.add(packageId);
            owner.assignPackage(packageId);
        }

        // Update task status
        TaskInfo task = knownTasks.get(packageId);
        if (task != null) {
            task.assignedRobot = robotId;
            task.isAssigned = true;
        }
    }

    private void handlePositionUpdate(CoordinationMessage msg) {
        // Parse position data
        String data = msg.getData();
        int robotId = msg.getEmitter();
        int x = Integer.parseInt(data.split("\"x\":")[1].split(",")[0]);
        int y = Integer.parseInt(data.split("\"y\":")[1].split(",")[0]);
        int destX = Integer.parseInt(data.split("\"destX\":")[1].split(",")[0]);
        int destY = Integer.parseInt(data.split("\"destY\":")[1].split(",")[0]);
        boolean hasPackage = Boolean.parseBoolean(data.split("\"hasPackage\":")[1].split("}")[0]);

        // Update or create robot info
        RobotInfo robot = knownRobots.getOrDefault(robotId, new RobotInfo(robotId));
        robot.x = x;
        robot.y = y;
        robot.destX = destX;
        robot.destY = destY;
        robot.hasPackage = hasPackage;
        robot.lastUpdate = System.currentTimeMillis();

        knownRobots.put(robotId, robot);
    }

    private void handleZoneStatus(CoordinationMessage msg) {
        // Parse zone data
        String data = msg.getData();
        String zoneType = data.split("\"zoneType\":\"")[1].split("\"")[0];
        String zoneId = data.split("\"zoneId\":\"")[1].split("\"")[0];
        int x = Integer.parseInt(data.split("\"x\":")[1].split(",")[0]);
        int y = Integer.parseInt(data.split("\"y\":")[1].split(",")[0]);
        int packageCount = Integer.parseInt(data.split("\"packageCount\":")[1].split(",")[0]);
        int capacity = Integer.parseInt(data.split("\"capacity\":")[1].split("}")[0]);

        // Update zone info
        ZoneInfo zone = new ZoneInfo(zoneId, zoneType, x, y);
        zone.packageCount = packageCount;
        zone.capacity = capacity;
        zone.lastUpdate = System.currentTimeMillis();

        knownZones.put(zoneId, zone);
    }

    private void handleBatteryStatus(CoordinationMessage msg) {
        // Parse battery data
        String data = msg.getData();
        int robotId = msg.getEmitter();
        double batteryLevel = Double.parseDouble(data.split("\"batteryLevel\":")[1].split(",")[0]);
        double batteryCapacity = Double.parseDouble(data.split("\"batteryCapacity\":")[1].split(",")[0]);
        boolean isRecharging = Boolean.parseBoolean(data.split("\"isRecharging\":")[1].split("}")[0]);

        // Update robot info
        RobotInfo robot = knownRobots.getOrDefault(robotId, new RobotInfo(robotId));
        robot.batteryLevel = batteryLevel;
        robot.batteryCapacity = batteryCapacity;
        robot.isRecharging = isRecharging;
        robot.lastUpdate = System.currentTimeMillis();

        knownRobots.put(robotId, robot);
    }

    private void handleHandoffRequest(CoordinationMessage msg) {
        // Only handle if we're not carrying a package
        if (owner.isCarryingPackage())
            return;

        // Parse handoff request
        String data = msg.getData();
        int packageId = Integer.parseInt(data.split("\"packageId\":")[1].split(",")[0]);
        int x = Integer.parseInt(data.split("\"x\":")[1].split(",")[0]);
        int y = Integer.parseInt(data.split("\"y\":")[1].split(",")[0]);
        int destinationId = Integer.parseInt(data.split("\"destinationId\":")[1].split(",")[0]);

        // Calculate if we can handle this package
        double distanceToRobot = owner.distanceTo(x, y);
        double distanceToDestination = distanceToDestination(destinationId);
        double totalDistance = distanceToRobot + distanceToDestination;
        double batteryNeeded = totalDistance * 1.5; // With safety margin

        // Accept if we have enough battery and are close enough
        if (owner.getBatteryLevel() >= batteryNeeded && distanceToRobot <= 3) {
            // Send accept message
            String acceptData = String.format("{\"packageId\":%d,\"accepted\":true}", packageId);
            CoordinationMessage accept = new CoordinationMessage(
                    owner.getId(), CoordinationMessage.MessageType.HANDOFF_ACCEPT, acceptData, 7);

            // Send directly to the requesting robot
            owner.sendDirectMessage(msg.getEmitter(), accept.getContent());

            // Move toward the handoff location
            owner.setHandoffTarget(x, y, packageId, msg.getEmitter());
        }
    }

    private void handleHandoffAccept(CoordinationMessage msg) {
        // Only handle if we're carrying a package
        if (!owner.isCarryingPackage())
            return;

        // Parse handoff accept
        String data = msg.getData();
        int packageId = Integer.parseInt(data.split("\"packageId\":")[1].split(",")[0]);

        // Verify it's for our package
        if (packageId == owner.getCarriedPackageId()) {
            // Set the handoff target
            owner.initiateHandoff(msg.getEmitter());
        }
    }

    private void handlePathConflict(CoordinationMessage msg) {
        // Parse conflict data
        String data = msg.getData();
        int x = Integer.parseInt(data.split("\"x\":")[1].split(",")[0]);
        int y = Integer.parseInt(data.split("\"y\":")[1].split(",")[0]);

        // If we're heading to this location, adjust our path
        if (owner.getDestX() == x && owner.getDestY() == y) {
            // Check if the conflict is with a robot that has higher priority
            int conflictRobotId = msg.getEmitter();

            // Determine priority based on robot ID and battery level
            // Lower ID gets priority, but higher battery can override
            boolean shouldYield = false;

            // If the other robot has a lower ID, we yield
            if (conflictRobotId < owner.getId()) {
                shouldYield = true;
            }

            // Check if the other robot has much higher battery
            RobotInfo otherRobot = knownRobots.get(conflictRobotId);
            if (otherRobot != null) {
                // If the other robot has >50% more battery, they get priority
                if (otherRobot.batteryLevel > owner.getBatteryLevel() * 1.5) {
                    shouldYield = true;
                }

                // If we have >50% more battery, we get priority
                if (owner.getBatteryLevel() > otherRobot.batteryLevel * 1.5) {
                    shouldYield = false;
                }

                // If the other robot is carrying a package and we're not, they get priority
                if (otherRobot.hasPackage && !owner.isCarryingPackage()) {
                    shouldYield = true;
                }

                // If we're carrying a package and they're not, we get priority
                if (owner.isCarryingPackage() && !otherRobot.hasPackage) {
                    shouldYield = false;
                }
            }

            if (shouldYield) {
                // Find an alternative path
                owner.avoidLocation(x, y);

                // Wait a bit to let the other robot pass
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Clean up expired information
     * Called periodically by the robot
     */
    public void cleanupExpiredInfo() {
        long now = System.currentTimeMillis();

        // Clean up expired robot info
        Iterator<Map.Entry<Integer, RobotInfo>> robotIterator = knownRobots.entrySet().iterator();
        while (robotIterator.hasNext()) {
            Map.Entry<Integer, RobotInfo> entry = robotIterator.next();
            if (now - entry.getValue().lastUpdate > ROBOT_INFO_EXPIRY_MS) {
                robotIterator.remove();
            }
        }

        // Clean up expired zone info
        Iterator<Map.Entry<String, ZoneInfo>> zoneIterator = knownZones.entrySet().iterator();
        while (zoneIterator.hasNext()) {
            Map.Entry<String, ZoneInfo> entry = zoneIterator.next();
            if (now - entry.getValue().lastUpdate > ZONE_INFO_EXPIRY_MS) {
                zoneIterator.remove();
            }
        }
    }

    /**
     * Check if a task is assigned to us
     */
    public boolean isTaskAssignedToUs(int packageId) {
        return assignedTasks.contains(packageId);
    }

    /**
     * Get known robots near a location
     */
    public List<RobotInfo> getRobotsNear(int x, int y, double maxDistance) {
        List<RobotInfo> result = new ArrayList<>();
        for (RobotInfo robot : knownRobots.values()) {
            double distance = Math.sqrt(Math.pow(robot.x - x, 2) + Math.pow(robot.y - y, 2));
            if (distance <= maxDistance) {
                result.add(robot);
            }
        }
        return result;
    }

    // Inner classes for data storage

    public static class TaskInfo {
        public int packageId;
        public String startZone;
        public int destinationId;
        public double urgency;
        public long creationTime;
        public int announcer;
        public int assignedRobot;
        public boolean isAssigned;

        public TaskInfo(int packageId, String startZone, int destinationId, double urgency, long creationTime) {
            this.packageId = packageId;
            this.startZone = startZone;
            this.destinationId = destinationId;
            this.urgency = urgency;
            this.creationTime = creationTime;
            this.isAssigned = false;
        }
    }

    public static class TaskBid {
        public int robotId;
        public int packageId;
        public double fitness;
        public double batteryLevel;

        public TaskBid(int robotId, int packageId, double fitness, double batteryLevel) {
            this.robotId = robotId;
            this.packageId = packageId;
            this.fitness = fitness;
            this.batteryLevel = batteryLevel;
        }
    }

    public static class RobotInfo {
        public int id;
        public int x;
        public int y;
        public int destX;
        public int destY;
        public boolean hasPackage;
        public double batteryLevel;
        public double batteryCapacity;
        public boolean isRecharging;
        public long lastUpdate;

        public RobotInfo(int id) {
            this.id = id;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    public static class ZoneInfo {
        public String id;
        public String type;
        public int x;
        public int y;
        public int packageCount;
        public int capacity;
        public long lastUpdate;

        public ZoneInfo(String id, String type, int x, int y) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.lastUpdate = System.currentTimeMillis();
        }
    }
}
