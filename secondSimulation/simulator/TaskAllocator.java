package simulator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advanced task allocation system with dynamic reassignment and priority
 * management
 */
public class TaskAllocator {

    private MyRobot owner;
    private static Map<Integer, TaskInfo> globalTasks = new ConcurrentHashMap<>();
    private static Map<Integer, Set<Integer>> taskBidders = new ConcurrentHashMap<>();
    private static Map<Integer, Long> taskDeadlines = new ConcurrentHashMap<>();

    // Task priority factors
    private static final double DISTANCE_WEIGHT = 0.3;
    private static final double BATTERY_WEIGHT = 0.25;
    private static final double LOAD_WEIGHT = 0.2;
    private static final double URGENCY_WEIGHT = 0.15;
    private static final double SPECIALIZATION_WEIGHT = 0.1;

    // Timing constants
    private static final long AUCTION_DURATION = 1000; // 1 second
    private static final long TASK_TIMEOUT = 30000; // 30 seconds

    public TaskAllocator(MyRobot owner) {
        this.owner = owner;
    }

    /**
     * Announce a new task for auction
     */
    public void announceTask(int packageId, String startZone, int destinationId, double urgency) {
        TaskInfo task = new TaskInfo(packageId, startZone, destinationId, urgency);
        globalTasks.put(packageId, task);
        taskBidders.put(packageId, new HashSet<>());
        taskDeadlines.put(packageId, System.currentTimeMillis() + AUCTION_DURATION);

        // Broadcast task announcement
        CoordinationMessage announcement = CoordinationMessage.createTaskAnnouncement(
                owner.getId(), packageId, startZone, destinationId, urgency);
        owner.broadcastMessage(announcement.getContent());

        // Submit our own bid
        submitBid(packageId);
    }

    /**
     * Submit a bid for a task
     */
    public void submitBid(int packageId) {
        TaskInfo task = globalTasks.get(packageId);
        if (task == null || task.isAssigned) {
            return;
        }

        double fitness = calculateTaskFitness(task);

        // Only bid if we have reasonable fitness
        if (fitness > 0.1) {
            CoordinationMessage bid = CoordinationMessage.createTaskBid(
                    owner.getId(), packageId, fitness, owner.getBatteryLevel(),
                    getDistanceToStartZone(task.startZone));

            owner.broadcastMessage(bid.getContent());

            // Track our bid locally
            Set<Integer> bidders = taskBidders.get(packageId);
            if (bidders != null) {
                bidders.add(owner.getId());
            }
        }
    }

    /**
     * Calculate fitness for a task using multiple factors
     */
    private double calculateTaskFitness(TaskInfo task) {
        // Distance factor (closer is better)
        double distanceToStart = getDistanceToStartZone(task.startZone);
        double distanceFactor = 1.0 / (1.0 + distanceToStart);

        // Battery factor (more battery is better)
        double batteryRatio = (double) owner.getBatteryLevel() / owner.getBatteryCapacity();
        double batteryFactor = batteryRatio;

        // Load factor (less loaded robots are better)
        double loadFactor = owner.isCarryingPackage() ? 0.2 : 1.0;

        // Urgency factor (urgent tasks get priority)
        double urgencyFactor = task.urgency;

        // Specialization factor (robots specialize in certain zones/destinations)
        double specializationFactor = getSpecializationFactor(task);

        // Check if we have enough battery for the task
        double estimatedDistance = distanceToStart + getDistanceToDestination(task.destinationId);
        double batteryNeeded = estimatedDistance * 1.5; // Safety margin

        if (owner.getBatteryLevel() < batteryNeeded) {
            batteryFactor *= 0.3; // Heavy penalty if insufficient battery
        }

        // Combine all factors
        return (distanceFactor * DISTANCE_WEIGHT) +
                (batteryFactor * BATTERY_WEIGHT) +
                (loadFactor * LOAD_WEIGHT) +
                (urgencyFactor * URGENCY_WEIGHT) +
                (specializationFactor * SPECIALIZATION_WEIGHT);
    }

    /**
     * Get specialization factor based on robot's preferred zones
     */
    private double getSpecializationFactor(TaskInfo task) {
        // Robots specialize based on their ID and grid quadrants
        int robotId = owner.getId();

        // Assign robots to different zones based on their ID
        int preferredZone = robotId % 3; // 3 start zones (A1, A2, A3)

        String[] zones = { "A1", "A2", "A3" };
        if (preferredZone < zones.length && zones[preferredZone].equals(task.startZone)) {
            return 1.0; // High specialization for preferred zone
        }

        // Also consider destination specialization
        int preferredDestination = (robotId % 2) + 1; // Destinations 1 or 2
        if (preferredDestination == task.destinationId) {
            return 0.8; // Medium specialization for preferred destination
        }

        return 0.5; // Default specialization
    }

    /**
     * Process task auctions and assign winners
     */
    public void processAuctions() {
        long currentTime = System.currentTimeMillis();
        List<Integer> expiredAuctions = new ArrayList<>();

        // Find expired auctions
        for (Map.Entry<Integer, Long> entry : taskDeadlines.entrySet()) {
            if (currentTime > entry.getValue()) {
                expiredAuctions.add(entry.getKey());
            }
        }

        // Process each expired auction
        for (int packageId : expiredAuctions) {
            TaskInfo task = globalTasks.get(packageId);
            if (task != null && !task.isAssigned) {
                assignTaskToWinner(packageId, task);
            }

            // Clean up
            taskDeadlines.remove(packageId);
            taskBidders.remove(packageId);
        }
    }

    /**
     * Assign task to the best bidder
     */
    private void assignTaskToWinner(int packageId, TaskInfo task) {
        // For simplicity, we'll use a basic winner selection
        // In a real implementation, this would collect and compare all bids

        Set<Integer> bidders = taskBidders.get(packageId);
        if (bidders == null || bidders.isEmpty()) {
            return;
        }

        // If we're among the bidders and the task announcer, we can assign it
        if (bidders.contains(owner.getId()) && task.announcer == owner.getId()) {
            // Simple assignment: assign to ourselves if we bid
            task.assignedRobot = owner.getId();
            task.isAssigned = true;

            // Broadcast assignment
            String data = String.format("{\"packageId\":%d,\"robotId\":%d}", packageId, owner.getId());
            CoordinationMessage assignment = new CoordinationMessage(
                    owner.getId(), CoordinationMessage.MessageType.TASK_ASSIGNMENT, data, 8);
            owner.broadcastMessage(assignment.getContent());

            // Assign to ourselves
            owner.assignPackage(packageId);
        }
    }

    /**
     * Handle incoming task assignment
     */
    public void handleTaskAssignment(int packageId, int assignedRobotId) {
        TaskInfo task = globalTasks.get(packageId);
        if (task != null) {
            task.assignedRobot = assignedRobotId;
            task.isAssigned = true;

            if (assignedRobotId == owner.getId()) {
                owner.assignPackage(packageId);
            }
        }
    }

    /**
     * Check if a task should be reassigned due to changed conditions
     */
    public boolean shouldReassignTask(int packageId) {
        TaskInfo task = globalTasks.get(packageId);
        if (task == null || !task.isAssigned || task.assignedRobot != owner.getId()) {
            return false;
        }

        // Check if task has been running too long
        long taskAge = System.currentTimeMillis() - task.creationTime;
        if (taskAge > TASK_TIMEOUT) {
            return true;
        }

        // Check if robot's battery is too low to complete the task
        double estimatedDistance = getDistanceToStartZone(task.startZone) +
                getDistanceToDestination(task.destinationId);
        double batteryNeeded = estimatedDistance * 1.5;

        if (owner.getBatteryLevel() < batteryNeeded) {
            return true;
        }

        return false;
    }

    /**
     * Request task reassignment
     */
    public void requestTaskReassignment(int packageId) {
        TaskInfo task = globalTasks.get(packageId);
        if (task != null) {
            task.isAssigned = false;
            task.assignedRobot = -1;

            // Re-announce the task with higher urgency
            announceTask(packageId, task.startZone, task.destinationId, Math.min(2.0, task.urgency * 1.5));
        }
    }

    /**
     * Get distance to start zone
     */
    private double getDistanceToStartZone(String zoneId) {
        // This would normally use zone coordinates from the coordinator
        // For now, use a simple mapping
        Map<String, int[]> zonePositions = Map.of(
                "A1", new int[] { 6, 19 },
                "A2", new int[] { 9, 19 },
                "A3", new int[] { 12, 19 });

        int[] zonePos = zonePositions.get(zoneId);
        if (zonePos != null) {
            return owner.distanceTo(zonePos[0], zonePos[1]);
        }

        return 100.0; // Default high distance
    }

    /**
     * Get distance to destination
     */
    private double getDistanceToDestination(int destinationId) {
        int[] goalPos = owner.getGoalPosition(destinationId);
        if (goalPos != null) {
            return owner.distanceTo(goalPos[0], goalPos[1]);
        }

        return 100.0; // Default high distance
    }

    /**
     * Clean up old tasks
     */
    public void cleanupOldTasks() {
        long currentTime = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, TaskInfo>> iterator = globalTasks.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Integer, TaskInfo> entry = iterator.next();
            TaskInfo task = entry.getValue();

            // Remove tasks older than 60 seconds
            if (currentTime - task.creationTime > 60000) {
                iterator.remove();
            }
        }
    }

    /**
     * Task information class
     */
    public static class TaskInfo {
        public int packageId;
        public String startZone;
        public int destinationId;
        public double urgency;
        public long creationTime;
        public int announcer;
        public int assignedRobot = -1;
        public boolean isAssigned = false;

        public TaskInfo(int packageId, String startZone, int destinationId, double urgency) {
            this.packageId = packageId;
            this.startZone = startZone;
            this.destinationId = destinationId;
            this.urgency = urgency;
            this.creationTime = System.currentTimeMillis();
        }
    }
}
