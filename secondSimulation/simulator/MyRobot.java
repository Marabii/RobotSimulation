package simulator;

import fr.emse.fayol.maqit.simulator.components.ColorInteractionRobot;
import fr.emse.fayol.maqit.simulator.components.ColorPackage;
import fr.emse.fayol.maqit.simulator.components.ColorStartZone;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.components.Orientation;
import fr.emse.fayol.maqit.simulator.components.PackageState;

import fr.emse.fayol.maqit.simulator.environment.Cell;
import fr.emse.fayol.maqit.simulator.environment.ColorCell;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;
import fr.emse.fayol.maqit.simulator.environment.Location;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

/**
 * A basic robot that:
 * 1. Looks for a start zone with a package,
 * 2. Takes it and delivers it directly to the final goal,
 * 3. Disappears after delivery.
 *
 * Extended to:
 * - Manage a battery & recharging
 * - Avoid getting stuck by proactively charging
 * - Use broadcastMessage(...) for simple communication
 */
public class MyRobot extends ColorInteractionRobot {

    protected enum Etat {
        FREE, TRANSPORT, DELIVRE
    }

    protected Etat etat;
    public ColorPackage carriedPackage;
    protected int destX;
    protected int destY;
    protected long tempsDepart;
    protected long tempsArrivee;
    protected ColorGridEnvironment env;

    // ------------- Battery Management -------------
    protected int batteryLevel;
    protected final int batteryCapacity = 150; // Moderately increased battery capacity for longer operation
    protected final int rechargeTime = 12; // Slightly longer recharge time for larger battery
    protected int rechargeCounter = 0;
    protected boolean isRecharging = false;

    // *** ADDED or CHANGED *** (Multiple charging stations)
    /** A list of all known charging-station locations. (row, col) */
    protected List<int[]> chargingStations;

    // ------------- For communication -------------
    protected boolean communicationEnabled = true;

    // ------------- Coordination -------------
    protected TaskCoordinator coordinator;
    protected int handoffTargetRobot = -1;
    protected int handoffPackageId = -1;
    protected int handoffX = -1;
    protected int handoffY = -1;
    protected boolean isWaitingForHandoff = false;
    protected boolean isInitiatingHandoff = false;
    protected long lastStatusBroadcast = 0;
    protected long lastCoordinationCheck = 0;
    protected static final long STATUS_BROADCAST_INTERVAL = 2000; // ms
    protected static final long COORDINATION_CHECK_INTERVAL = 1000; // ms

    // ------------- Enhanced Optimization Components -------------
    protected PathPlanner pathPlanner;
    protected BatteryManager batteryManager;
    protected TaskAllocator taskAllocator;
    protected int[] reservedChargingStation = null;
    protected List<int[]> currentPath = null;
    protected int pathIndex = 0;
    protected long lastPathUpdate = 0;
    protected static final long PATH_UPDATE_INTERVAL = 2000; // ms

    // ------------- Timeout and Task Management -------------
    protected long currentTaskStartTime = 0;
    protected static final long TASK_TIMEOUT = 60000; // 1 minute timeout for tasks
    protected boolean taskTimedOut = false;

    /**
     * List of final goals (id -> [x, y])
     */
    protected static final Map<Integer, int[]> GOALS = new HashMap<>();
    static {
        GOALS.put(1, new int[] { 5, 0 }); // Z1
        GOALS.put(2, new int[] { 15, 0 }); // Z2
    }

    // Store start zone positions
    protected List<int[]> startZoneList;

    public MyRobot(String name, int field, int debug, int[] pos, Color color,
            int rows, int columns, ColorGridEnvironment env, long seed,
            List<int[]> startZonePositions,
            List<int[]> chargingStations // *** ADDED or CHANGED ***
    ) {
        super(name, field, debug, pos, color, rows, columns, seed);
        this.env = env;
        this.etat = Etat.FREE;
        this.carriedPackage = null;
        this.startZoneList = startZonePositions;

        // *** ADDED or CHANGED ***
        this.chargingStations = chargingStations; // store the multiple chargers

        // Initialize the task coordinator
        this.coordinator = new TaskCoordinator(this);

        // Initialize enhanced optimization components
        this.pathPlanner = new PathPlanner(env, rows, columns);
        this.batteryManager = new BatteryManager(this, chargingStations);
        this.taskAllocator = new TaskAllocator(this);

        randomOrientation();
        this.batteryLevel = batteryCapacity;
    }

    // ---------- Battery & Charging Logic ----------
    /**
     * Checks if the robot is on a charging zone or adjacent to it (including
     * diagonally).
     *
     * @return true if the robot can charge at its current position
     */
    protected boolean onChargingZone() {
        for (int[] station : chargingStations) {
            if (isAdjacentTo(station[0], station[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Main battery logic:
     * - If already recharging, count down until done.
     * - If battery hits 0 away from charger, robot becomes stranded (no emergency
     * boost).
     * - If on a charging zone, stay until fully recharged.
     * - Robots must be fully charged before leaving charging stations.
     */
    protected void manageBattery() {
        if (isRecharging) {
            rechargeCounter++;
            if (rechargeCounter >= rechargeTime) {
                // Full recharge
                batteryLevel = batteryCapacity;
                isRecharging = false;
                rechargeCounter = 0;
                System.out.println(getName() + " has recharged fully and can now leave the charging station.");
            }
            return; // skip movement while recharging
        }

        // If battery is 0 and not on a charging zone => robot is stranded
        if (batteryLevel <= 0) {
            if (onChargingZone()) {
                isRecharging = true;
                rechargeCounter = 0;
                System.out.println(getName() + " starts emergency recharging at charging station...");
            } else {
                // Robot is stranded - no emergency battery boost
                System.out.println(getName() + " is stranded with no battery! Robot cannot move.");
                // Mark task as failed if carrying a package
                if (isCarryingPackage()) {
                    System.out.println(getName() + " failed to deliver package due to battery depletion.");
                    taskTimedOut = true;
                }
            }
            return;
        }

        // If on a charging zone and not fully charged, start recharging
        // This ensures robots stay at the station until fully recharged
        if (onChargingZone() && batteryLevel < batteryCapacity) {
            isRecharging = true;
            rechargeCounter = 0;
            System.out.println(getName() + " is at a charging station and starts recharging...");
            return;
        }
    }

    /**
     * Broadcast a simple message to all other robots.
     */
    public void broadcastMessage(String content) {
        if (!communicationEnabled)
            return;

        for (var r : env.getRobot()) {
            if (r != this) {
                handleMessage(new Message(r.getId(), content));
            }
        }
    }

    // ---------- Utility Methods ----------
    protected ColorStartZone findStartZoneWithPackage() {
        for (int[] pos : startZoneList) {
            Cell c = env.getGrid()[pos[0]][pos[1]];
            if (c instanceof ColorCell && c.getContent() instanceof ColorStartZone) {
                ColorStartZone zone = (ColorStartZone) c.getContent();
                if (!zone.getPackages().isEmpty()) {
                    return zone;
                }
            }
        }
        return null;
    }

    /**
     * Checks if the robot is adjacent to or on the same position as the given
     * coordinates.
     * This includes orthogonal (up, down, left, right) and diagonal adjacency.
     */
    protected boolean isAdjacentTo(int row, int col) {
        // Check if robot is at the exact position
        if (this.getX() == row && this.getY() == col) {
            return true;
        }

        // Check for orthogonal and diagonal adjacency
        // For diagonal adjacency, both x and y differ by at most 1
        return Math.abs(this.getX() - row) <= 1 && Math.abs(this.getY() - col) <= 1;
    }

    private boolean isCellFree(int x, int y) {
        Cell c = env.getGrid()[x][y];
        return (c == null || c.getContent() == null);
    }

    private double distanceBetween(int x1, int y1, int x2, int y2) {
        return Math.sqrt(Math.pow((x2 - x1), 2) + Math.pow((y2 - y1), 2));
    }

    /**
     * Distance from the robot's location to a given (row, col).
     */
    protected double distanceTo(int row, int col) {
        return distanceBetween(this.getX(), this.getY(), row, col);
    }

    public boolean isActive() {
        return etat != Etat.DELIVRE;
    }

    /**
     * Attempt to move 1 step toward (targetX, targetY) with improved collision
     * avoidance.
     * This version considers other robots' positions and planned movements.
     */
    protected void moveOneStepTo(int targetX, int targetY) {
        // If we're already at the target, do nothing
        if (this.getX() == targetX && this.getY() == targetY) {
            return;
        }

        // Get all possible next positions
        HashMap<String, Location> directions = getNextCoordinate();

        // First, check if there are other robots heading to our target
        boolean targetConflict = isPositionOccupiedByRobot(targetX, targetY);
        if (targetConflict) {
            // If our target is already occupied, we need to wait or find an alternative
            // Broadcast a conflict message so other robots know about it
            if (coordinator != null) {
                CoordinationMessage conflict = CoordinationMessage.createPathConflict(
                        getId(), targetX, targetY);
                broadcastMessage(conflict.getContent());
            }
        }

        // Find the best move considering other robots
        Location bestMove = null;
        double minDist = Double.MAX_VALUE;

        // First pass: find moves that don't collide with other robots
        for (Entry<String, Location> entry : directions.entrySet()) {
            Location loc = entry.getValue();

            // Skip invalid positions (out of bounds)
            if (loc.getX() < 0 || loc.getX() >= rows ||
                    loc.getY() < 0 || loc.getY() >= columns) {
                continue;
            }

            // Skip positions that aren't free
            if (!isCellFree(loc.getX(), loc.getY())) {
                continue;
            }

            // Skip positions that have robots or robots are heading to
            if (isPositionOccupiedByRobot(loc.getX(), loc.getY()) ||
                    isPositionTargetedByOtherRobot(loc.getX(), loc.getY())) {
                continue;
            }

            // Calculate distance to target
            double dist = distanceBetween(loc.getX(), loc.getY(), targetX, targetY);

            // If this is better than our current best, update it
            if (dist < minDist) {
                minDist = dist;
                bestMove = loc;
            }
        }

        // If we didn't find a collision-free move, try again but allow positions
        // that might have robots heading to them (less strict)
        if (bestMove == null) {
            for (Entry<String, Location> entry : directions.entrySet()) {
                Location loc = entry.getValue();

                // Skip invalid positions
                if (loc.getX() < 0 || loc.getX() >= rows ||
                        loc.getY() < 0 || loc.getY() >= columns) {
                    continue;
                }

                // Skip positions that aren't free
                if (!isCellFree(loc.getX(), loc.getY())) {
                    continue;
                }

                // Skip positions that have robots (but allow targeted positions)
                if (isPositionOccupiedByRobot(loc.getX(), loc.getY())) {
                    continue;
                }

                // Calculate distance to target
                double dist = distanceBetween(loc.getX(), loc.getY(), targetX, targetY);

                // If this is better than our current best, update it
                if (dist < minDist) {
                    minDist = dist;
                    bestMove = loc;
                }
            }
        }

        // If we found a valid move, execute it
        if (bestMove != null) {
            // Adjust orientation based on the move
            if (bestMove.getX() == this.getX() - 1)
                setCurrentOrientation(Orientation.up);
            else if (bestMove.getX() == this.getX() + 1)
                setCurrentOrientation(Orientation.down);
            else if (bestMove.getY() == this.getY() - 1)
                setCurrentOrientation(Orientation.left);
            else if (bestMove.getY() == this.getY() + 1)
                setCurrentOrientation(Orientation.right);

            // Execute the move
            moveForward();

            // Decrement battery (reduced consumption)
            if (batteryLevel > 0) {
                batteryLevel--;
            }

            // Broadcast our new position to help other robots avoid us
            if (coordinator != null) {
                coordinator.broadcastStatus();
            }
        } else {
            // If we couldn't find any valid move, try a random orientation
            // This helps break deadlocks
            randomOrientation();
        }
    }

    /**
     * Check if a position is currently occupied by another robot
     */
    protected boolean isPositionOccupiedByRobot(int x, int y) {
        for (var robot : env.getRobot()) {
            if (robot != this && robot.getX() == x && robot.getY() == y) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a position is the target destination of another robot
     */
    protected boolean isPositionTargetedByOtherRobot(int x, int y) {
        if (coordinator == null)
            return false;

        for (var robot : env.getRobot()) {
            if (robot != this && robot instanceof MyRobot) {
                MyRobot otherRobot = (MyRobot) robot;
                if (otherRobot.getDestX() == x && otherRobot.getDestY() == y) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- Improved Charging Logic ----------
    /**
     * Return the nearest charging-station coordinates from this robot's position.
     */
    protected int[] getNearestChargingStation() {
        int[] nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (int[] station : chargingStations) {
            double d = distanceTo(station[0], station[1]);
            if (d < bestDist) {
                bestDist = d;
                nearest = station;
            }
        }
        return nearest;
    }

    /**
     * Enhanced charging logic with alternative path finding and safety checks
     */
    protected void goCharge() {
        // First check if we can reach any charging station
        if (!batteryManager.canReachAnyChargingStation()) {
            System.out.println(getName() + " cannot reach any charging station with current battery!");
            // Robot is effectively stranded
            return;
        }

        // Use BatteryManager to get the best charging station
        int[] bestStation = batteryManager.getBestChargingStation();

        // If primary station is not available, try alternative
        if (bestStation == null) {
            bestStation = batteryManager.findAlternativeChargingPath();
            if (bestStation == null) {
                System.out.println(getName() + ": No charging stations available!");
                return;
            }
        }

        // Reserve the station if we haven't already
        if (reservedChargingStation == null ||
                !java.util.Arrays.equals(reservedChargingStation, bestStation)) {

            // Release previous reservation
            if (reservedChargingStation != null) {
                batteryManager.releaseChargingStation(reservedChargingStation);
            }

            // Reserve new station
            batteryManager.reserveChargingStation(bestStation);
            reservedChargingStation = bestStation;
        }

        // If we're already on a charging zone
        if (onChargingZone()) {
            isRecharging = true;
            rechargeCounter = 0;
            System.out.println(getName() + " starts recharging at station (" +
                    bestStation[0] + "," + bestStation[1] + ")");
        } else {
            // Get optimal approach position for load distribution
            int[] approachPos = batteryManager.getChargingApproachPosition(bestStation);

            // If we're already at the approach position, wait
            if (this.getX() == approachPos[0] && this.getY() == approachPos[1]) {
                System.out.println(getName() + " is at approach position for charging station.");
                return;
            }

            // Use battery-aware pathfinding to move toward charging station
            moveToTargetWithPathfinding(bestStation[0], bestStation[1]);
        }
    }

    /**
     * Enhanced movement using A* pathfinding with battery-aware dynamic obstacle
     * avoidance
     */
    protected void moveToTargetWithPathfinding(int targetX, int targetY) {
        long currentTime = System.currentTimeMillis();

        // Update path if needed (every 2 seconds or if no current path)
        if (currentPath == null || currentTime - lastPathUpdate > PATH_UPDATE_INTERVAL ||
                pathIndex >= currentPath.size()) {

            // Get dynamic obstacles (other robots)
            Set<String> dynamicObstacles = new HashSet<>();
            for (var robot : env.getRobot()) {
                if (robot != this) {
                    dynamicObstacles.add(robot.getX() + "," + robot.getY());
                }
            }

            // Use battery-aware pathfinding with safety margin
            currentPath = pathPlanner.findBatteryAwarePath(
                    this.getX(), this.getY(), targetX, targetY,
                    dynamicObstacles, batteryLevel, 1.2);

            pathIndex = 0;
            lastPathUpdate = currentTime;

            if (currentPath.isEmpty()) {
                // Check if we have enough battery for simple movement
                double directDistance = distanceTo(targetX, targetY);
                double requiredBattery = directDistance * 1.2; // Reduced safety margin for fallback

                if (batteryLevel >= requiredBattery) {
                    System.out.println(getName() + " no battery-safe path found, using simple movement (need " +
                            requiredBattery + ", have " + batteryLevel + ")");
                    moveOneStepTo(targetX, targetY);
                    return;
                } else if (batteryLevel >= directDistance) {
                    // Even more aggressive fallback - just try to move if we have enough for direct
                    // distance
                    System.out.println(getName() + " using aggressive fallback movement (need " +
                            directDistance + ", have " + batteryLevel + ")");
                    moveOneStepTo(targetX, targetY);
                    return;
                } else {
                    System.out.println(getName() + " insufficient battery for any movement (need " +
                            directDistance + ", have " + batteryLevel + "), going to charge");
                    goCharge();
                    return;
                }
            } else {
                double pathCost = pathPlanner.calculatePathBatteryCost(currentPath);
                System.out.println(getName() + " found battery-safe path with " + currentPath.size() +
                        " steps (cost: " + pathCost + " battery)");
            }
        }

        // Follow the current path
        if (pathIndex < currentPath.size()) {
            int[] nextStep = currentPath.get(pathIndex);

            // Check if we're already at the next step
            if (this.getX() == nextStep[0] && this.getY() == nextStep[1]) {
                pathIndex++;
                return;
            }

            // Move toward next step
            moveOneStepTo(nextStep[0], nextStep[1]);

            // Check if we reached the next step
            if (this.getX() == nextStep[0] && this.getY() == nextStep[1]) {
                pathIndex++;
            }
        } else {
            // Path completed, use simple movement for final approach
            moveOneStepTo(targetX, targetY);
        }
    }

    // ---------- Robot Logic (step) ----------
    public void step() {
        // (Optional) log charging status - disabled to reduce noise
        // logChargingStatus();

        // 1) Battery housekeeping
        manageBattery();
        // If recharging or out of battery, skip logic
        if (isRecharging || batteryLevel <= 0) {
            return;
        }

        // Periodic coordination tasks
        long currentTime = System.currentTimeMillis();

        // Broadcast status periodically
        if (currentTime - lastStatusBroadcast > STATUS_BROADCAST_INTERVAL) {
            coordinator.broadcastStatus();
            lastStatusBroadcast = currentTime;
        }

        // Run coordination checks periodically
        if (currentTime - lastCoordinationCheck > COORDINATION_CHECK_INTERVAL) {
            // Process any active auctions (both old and new systems)
            coordinator.processAuctions();
            taskAllocator.processAuctions();

            // Consider package handoffs if carrying a package
            if (isCarryingPackage()) {
                coordinator.considerHandoffs();
            }

            // Clean up expired information
            coordinator.cleanupExpiredInfo();
            taskAllocator.cleanupOldTasks();

            lastCoordinationCheck = currentTime;
        }

        // Handle handoff logic if active
        if (handleHandoffLogic()) {
            return; // Skip regular logic if handling a handoff
        }

        // 2) Check for task timeout
        if (currentTaskStartTime > 0 && currentTime - currentTaskStartTime > TASK_TIMEOUT) {
            System.out.println(getName() + " task timed out after " + TASK_TIMEOUT + "ms. Abandoning current task.");
            taskTimedOut = true;
            if (isCarryingPackage()) {
                // Drop the package and return to free state
                carriedPackage = null;
                etat = Etat.FREE;
            }
            currentTaskStartTime = 0;
        }

        // 3) Enhanced battery management using BatteryManager with route validation
        if (batteryManager.shouldCharge()) {
            // If carrying a package and low on battery, try handoff first
            if (isCarryingPackage() && !isInitiatingHandoff && !isWaitingForHandoff) {
                if (attemptPackageHandoff()) {
                    return; // Handoff initiated, continue with handoff process
                }
            }

            // Only print charging message occasionally to reduce spam
            if (System.currentTimeMillis() % 1000 < 50) {
                System.out.println(getName() + " battery status: " + batteryManager.getBatteryStatus() +
                        " (" + batteryLevel + "/" + batteryCapacity + ") - heading to charge");
            }
            goCharge();
            return;
        }

        // 4) Validate route before starting any new task (only for FREE robots)
        if (etat == Etat.FREE && !taskTimedOut) {
            ColorStartZone zone = findStartZoneWithPackage();
            if (zone != null) {
                // Validate that we can complete the entire route before starting
                if (!batteryManager.canCompleteFullRoute(zone.getX(), zone.getY(),
                        zone.getPackages().get(0).getDestinationGoalId())) {
                    System.out.println(getName() + " cannot complete route to package at (" + zone.getX() + ","
                            + zone.getY() + ") - insufficient battery");
                    // Go charge instead of attempting the task
                    goCharge();
                    return;
                }
            }
        }

        // 3) If we have already delivered, do nothing
        if (etat == Etat.DELIVRE) {
            return;
        }

        // 5) Otherwise: pick from start zone, or deliver
        if (etat == Etat.FREE && !taskTimedOut) {
            ColorStartZone zone = findStartZoneWithPackage();
            if (zone == null) {
                // No package is available, do nothing
                taskTimedOut = false; // Reset timeout flag when no tasks available
                return;
            }
            if (isAdjacentTo(zone.getX(), zone.getY())) {
                if (!zone.getPackages().isEmpty()) {
                    carriedPackage = zone.getPackages().get(0);
                    zone.removePackage(carriedPackage);

                    tempsDepart = System.currentTimeMillis();
                    currentTaskStartTime = tempsDepart; // Start timeout tracking
                    taskTimedOut = false; // Reset timeout flag

                    int[] goalPos = GOALS.get(carriedPackage.getDestinationGoalId());
                    if (goalPos != null) {
                        destX = goalPos[0];
                        destY = goalPos[1];
                        etat = Etat.TRANSPORT;
                    }
                    System.out.println(getName() + " took a package from "
                            + carriedPackage.getStartZone()
                            + " to deliver to goal " + carriedPackage.getDestinationGoalId()
                            + " at position (" + destX + "," + destY + ")");

                    // Announce this task using enhanced task allocation
                    taskAllocator.announceTask(
                            carriedPackage.getId(),
                            carriedPackage.getStartZone(),
                            carriedPackage.getDestinationGoalId(),
                            1.0 // default urgency
                    );
                }
            } else {
                // Temporarily use simple movement to test core improvements
                moveOneStepTo(zone.getX(), zone.getY());
            }
        } else if (taskTimedOut) {
            // Reset timeout flag and go charge to recover
            System.out.println(getName() + " recovering from timeout - going to charge");
            taskTimedOut = false;
            goCharge();
            return;
        } else if (etat == Etat.TRANSPORT) {
            // Check if task should be reassigned due to changed conditions
            if (taskAllocator.shouldReassignTask(carriedPackage.getId())) {
                System.out.println(getName() + " requesting task reassignment for package " + carriedPackage.getId());
                taskAllocator.requestTaskReassignment(carriedPackage.getId());
                carriedPackage = null;
                etat = Etat.FREE;
                return;
            }

            // Check if we're at the destination (exact match or adjacent)
            if ((this.getX() == destX && this.getY() == destY) ||
                    (Math.abs(this.getX() - destX) <= 1 && Math.abs(this.getY() - destY) <= 1)) {
                // Deliver
                carriedPackage.setState(PackageState.ARRIVED);
                MySimFactory.deliveredCount++;
                tempsArrivee = System.currentTimeMillis();
                etat = Etat.DELIVRE;

                // Release charging station reservation if we have one
                if (reservedChargingStation != null) {
                    batteryManager.releaseChargingStation(reservedChargingStation);
                    reservedChargingStation = null;
                }

                env.removeCellContent(this.getX(), this.getY());
                System.out.println(getName() + " has delivered the package at (" + this.getX() + "," + this.getY() +
                        ") near goal (" + destX + "," + destY + ") and disappears.");
            } else {
                // Debug: print current position and destination
                if (System.currentTimeMillis() % 2000 < 50) {
                    System.out.println(getName() + " moving from (" + this.getX() + "," + this.getY() +
                            ") toward (" + destX + "," + destY + ") - distance: " +
                            distanceTo(destX, destY));
                }
                // Temporarily use simple movement to test core improvements
                moveOneStepTo(destX, destY);
            }
        }
    }

    /**
     * Handle package handoff logic
     *
     * @return true if handling a handoff, false otherwise
     */
    protected boolean handleHandoffLogic() {
        // If we're waiting to receive a handoff
        if (isWaitingForHandoff) {
            // Move to the handoff location
            if (this.getX() == handoffX && this.getY() == handoffY) {
                // We've reached the handoff location, wait for the package
                return true; // Skip regular logic
            } else {
                // Move toward the handoff location
                moveOneStepTo(handoffX, handoffY);
                return true; // Skip regular logic
            }
        }

        // If we're initiating a handoff
        if (isInitiatingHandoff && isCarryingPackage()) {
            // Find the target robot
            for (var r : env.getRobot()) {
                if (r.getId() == handoffTargetRobot && r instanceof MyRobot) {
                    MyRobot target = (MyRobot) r;

                    // If we're adjacent to the target robot
                    if (isAdjacentTo(target.getX(), target.getY())) {
                        // Perform the handoff
                        if (target.receivePackage(carriedPackage)) {
                            // Handoff successful
                            carriedPackage = null;
                            etat = Etat.FREE;
                            isInitiatingHandoff = false;
                            System.out.println(getName() + " handed off package to " + target.getName());
                            return true;
                        }
                    } else {
                        // Move toward the target robot
                        moveOneStepTo(target.getX(), target.getY());
                        return true;
                    }
                }
            }

            // If we couldn't find the target robot or handoff failed
            if (isInitiatingHandoff) {
                // Cancel the handoff after a timeout
                isInitiatingHandoff = false;
                System.out.println(getName() + " handoff failed, continuing delivery");
            }
        }

        return false; // Not handling a handoff
    }

    /**
     * Logs whether this robot is recharging, plus distance to the nearest charger.
     */
    public void logChargingStatus() {
        int[] nearest = getNearestChargingStation();
        double distance = (nearest == null) ? -1 : distanceTo(nearest[0], nearest[1]);
        String status = isRecharging ? "is currently recharging" : "is not recharging";
        System.out.println(getName() + " " + status
                + (distance >= 0 ? (" and is " + distance + " units away from the nearest charger.") : ""));
    }

    @Override
    public void handleMessage(Message msg) {
        // Try to parse as a coordination message
        CoordinationMessage coordMsg = CoordinationMessage.fromMessage(msg);
        if (coordMsg != null) {
            // Process through the coordinator
            coordinator.processMessage(coordMsg);
        } else {
            // Handle as a regular message
            System.out.println(getName() + " received a message: " + msg.getContent()
                    + " from " + msg.getEmitter());
        }
    }

    /**
     * Send a message directly to a specific robot
     */
    public void sendDirectMessage(int targetRobotId, String content) {
        if (!communicationEnabled)
            return;

        for (var r : env.getRobot()) {
            if (r.getId() == targetRobotId) {
                // Make sure it's a robot that can handle messages
                if (r instanceof MyRobot) {
                    ((MyRobot) r).handleMessage(new Message(this.getId(), content));
                }
                break;
            }
        }
    }

    @Override
    public void move(int step) {
        // By default, we just do step() once per cycle
        step();
    }

    // ---------- Coordination Helper Methods ----------

    /**
     * Check if this robot is carrying a package
     */
    public boolean isCarryingPackage() {
        return carriedPackage != null;
    }

    /**
     * Get the ID of the carried package
     */
    public int getCarriedPackageId() {
        return isCarryingPackage() ? carriedPackage.getId() : -1;
    }

    /**
     * Get the destination ID of the carried package
     */
    public int getCarriedPackageDestination() {
        return isCarryingPackage() ? carriedPackage.getDestinationGoalId() : -1;
    }

    /**
     * Get the current battery level
     */
    public int getBatteryLevel() {
        return batteryLevel;
    }

    /**
     * Get the battery capacity
     */
    public int getBatteryCapacity() {
        return batteryCapacity;
    }

    /**
     * Check if the robot is currently recharging
     */
    public boolean isRecharging() {
        return isRecharging;
    }

    /**
     * Get the destination X coordinate
     */
    public int getDestX() {
        return destX;
    }

    /**
     * Get the destination Y coordinate
     */
    public int getDestY() {
        return destY;
    }

    /**
     * Get the goal position for a destination ID
     */
    public int[] getGoalPosition(int destinationId) {
        return GOALS.get(destinationId);
    }

    /**
     * Get the number of rows in the grid
     */
    public int getGridRows() {
        return rows;
    }

    /**
     * Get the number of columns in the grid
     */
    public int getGridColumns() {
        return columns;
    }

    /**
     * Set a handoff target
     */
    public void setHandoffTarget(int x, int y, int packageId, int robotId) {
        handoffX = x;
        handoffY = y;
        handoffPackageId = packageId;
        handoffTargetRobot = robotId;
        isWaitingForHandoff = true;
    }

    /**
     * Initiate a handoff to another robot
     */
    public void initiateHandoff(int targetRobotId) {
        handoffTargetRobot = targetRobotId;
        isInitiatingHandoff = true;
    }

    /**
     * Receive a package from another robot
     */
    public boolean receivePackage(ColorPackage pkg) {
        // Only receive if we're not already carrying a package
        if (isCarryingPackage()) {
            return false;
        }

        // Accept the package
        carriedPackage = pkg;
        etat = Etat.TRANSPORT;

        // Set destination
        int[] goalPos = GOALS.get(pkg.getDestinationGoalId());
        if (goalPos != null) {
            destX = goalPos[0];
            destY = goalPos[1];
        }

        // Reset handoff state
        isWaitingForHandoff = false;

        System.out.println(getName() + " received package " + pkg.getId() + " via handoff");
        return true;
    }

    /**
     * Find a suitable robot for package handoff
     * Returns the robot that can best complete the delivery
     */
    public MyRobot findSuitableHandoffRobot() {
        if (!isCarryingPackage()) {
            return null;
        }

        MyRobot bestRobot = null;
        double bestScore = Double.MAX_VALUE;

        for (var r : env.getRobot()) {
            if (r != this && r instanceof MyRobot) {
                MyRobot candidate = (MyRobot) r;

                // Skip if robot is already carrying a package or recharging
                if (candidate.isCarryingPackage() || candidate.isRecharging()) {
                    continue;
                }

                // Skip if robot is too far away
                double distanceToCandidate = distanceTo(candidate.getX(), candidate.getY());
                if (distanceToCandidate > 10) { // Max handoff distance
                    continue;
                }

                // Check if candidate can complete the delivery
                double distanceToDestination = candidate.distanceTo(destX, destY);
                double batteryNeeded = distanceToDestination * 1.2; // Small buffer

                if (candidate.getBatteryLevel() < batteryNeeded) {
                    continue; // Candidate can't complete delivery either
                }

                // Calculate score (lower is better)
                double score = distanceToCandidate + (distanceToDestination * 0.5);

                if (score < bestScore) {
                    bestScore = score;
                    bestRobot = candidate;
                }
            }
        }

        return bestRobot;
    }

    /**
     * Attempt to handoff package to another robot
     * Returns true if handoff was initiated
     */
    public boolean attemptPackageHandoff() {
        if (!isCarryingPackage()) {
            return false;
        }

        MyRobot targetRobot = findSuitableHandoffRobot();
        if (targetRobot == null) {
            return false;
        }

        // Initiate handoff
        System.out.println(getName() + " initiating handoff of package " + carriedPackage.getId() +
                " to " + targetRobot.getName() + " due to low battery");

        // Set handoff target for the receiving robot
        targetRobot.setHandoffTarget(this.getX(), this.getY(), carriedPackage.getId(), this.getId());

        // Start our handoff process
        initiateHandoff(targetRobot.getId());

        return true;
    }

    /**
     * Assign a package to this robot (used by coordinator and task allocator)
     */
    public void assignPackage(int packageId) {
        // Handle assignment from both coordination systems
        System.out.println(getName() + " was assigned package " + packageId);

        // If we're not already carrying a package and not in delivery state
        if (!isCarryingPackage() && etat == Etat.FREE) {
            // The robot will pick up the package when it reaches the appropriate start zone
            // This is handled in the main step() logic
        }
    }

    /**
     * Avoid a specific location (used for collision avoidance)
     * This implementation finds an alternative path around the location
     */
    public void avoidLocation(int x, int y) {
        System.out.println(getName() + " avoiding location (" + x + ", " + y + ")");

        // If we're heading to this location, find an alternative
        if (destX == x && destY == y) {
            // We need to find a temporary waypoint to go around the conflict
            int[] waypoint = findAlternativeWaypoint(x, y);

            if (waypoint != null) {
                // Set a temporary destination
                int originalDestX = destX;
                int originalDestY = destY;

                // Temporarily change our destination to the waypoint
                destX = waypoint[0];
                destY = waypoint[1];

                System.out.println(getName() + " taking detour via (" + destX + ", " + destY + ") to reach (" +
                        originalDestX + ", " + originalDestY + ")");

                // After a few steps, we'll revert to the original destination
                // This is handled by a separate thread to avoid blocking
                new Thread(() -> {
                    try {
                        // Wait a bit before reverting to original destination
                        Thread.sleep(2000);
                        destX = originalDestX;
                        destY = originalDestY;
                        System.out.println(getName() + " resuming original path to (" + destX + ", " + destY + ")");
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }).start();
            }
        }
    }

    /**
     * Find an alternative waypoint to avoid a conflict
     */
    private int[] findAlternativeWaypoint(int x, int y) {
        // Define possible directions to try (8 directions)
        int[][] directions = {
                { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }, // Cardinal directions
                { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } // Diagonal directions
        };

        // Shuffle the directions based on robot ID to avoid all robots choosing the
        // same alternative
        shuffleDirections(directions, getId());

        // Try each direction to find a valid waypoint
        for (int[] dir : directions) {
            int newX = x + dir[0] * 2; // Go 2 steps in this direction
            int newY = y + dir[1] * 2;

            // Check if the position is valid
            if (newX >= 0 && newX < rows && newY >= 0 && newY < columns) {
                // Check if the cell is free and not targeted by another robot
                if (isCellFree(newX, newY) && !isPositionTargetedByOtherRobot(newX, newY)) {
                    return new int[] { newX, newY };
                }
            }
        }

        // If no good waypoint found, just return a position slightly offset from
        // current position
        int offsetX = (getId() % 2 == 0) ? 1 : -1;
        int offsetY = (getId() % 4 < 2) ? 1 : -1;

        int newX = Math.max(0, Math.min(rows - 1, this.getX() + offsetX));
        int newY = Math.max(0, Math.min(columns - 1, this.getY() + offsetY));

        return new int[] { newX, newY };
    }

    /**
     * Shuffle directions based on robot ID to ensure different robots choose
     * different alternatives
     */
    private void shuffleDirections(int[][] directions, int seed) {
        // Simple deterministic shuffle based on robot ID
        for (int i = 0; i < directions.length; i++) {
            int j = (i + seed) % directions.length;
            if (i != j) {
                int[] temp = directions[i];
                directions[i] = directions[j];
                directions[j] = temp;
            }
        }
    }
}
