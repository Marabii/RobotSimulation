package simulator;

import fr.emse.fayol.maqit.simulator.environment.Cell;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;
import java.util.*;

/**
 * Advanced path planning using A* algorithm with dynamic obstacle avoidance
 */
public class PathPlanner {

    private ColorGridEnvironment environment;
    private int rows;
    private int columns;

    public PathPlanner(ColorGridEnvironment environment, int rows, int columns) {
        this.environment = environment;
        this.rows = rows;
        this.columns = columns;
    }

    /**
     * Find optimal path using A* algorithm
     */
    public List<int[]> findPath(int startX, int startY, int goalX, int goalY, Set<String> dynamicObstacles) {
        PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Set<String> closedSet = new HashSet<>();
        Map<String, Node> allNodes = new HashMap<>();

        Node startNode = new Node(startX, startY, 0, heuristic(startX, startY, goalX, goalY));
        openSet.add(startNode);
        allNodes.put(startNode.getKey(), startNode);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            String currentKey = current.getKey();

            if (closedSet.contains(currentKey)) {
                continue;
            }

            closedSet.add(currentKey);

            // Check if we reached the goal
            if (current.x == goalX && current.y == goalY) {
                return reconstructPath(current);
            }

            // Explore neighbors
            for (int[] direction : getDirections()) {
                int newX = current.x + direction[0];
                int newY = current.y + direction[1];

                if (!isValidPosition(newX, newY, dynamicObstacles)) {
                    continue;
                }

                String neighborKey = newX + "," + newY;
                if (closedSet.contains(neighborKey)) {
                    continue;
                }

                double tentativeGCost = current.gCost + getMovementCost(current.x, current.y, newX, newY);

                Node neighbor = allNodes.get(neighborKey);
                if (neighbor == null) {
                    neighbor = new Node(newX, newY, tentativeGCost, heuristic(newX, newY, goalX, goalY));
                    neighbor.parent = current;
                    allNodes.put(neighborKey, neighbor);
                    openSet.add(neighbor);
                } else if (tentativeGCost < neighbor.gCost) {
                    neighbor.gCost = tentativeGCost;
                    neighbor.fCost = neighbor.gCost + neighbor.hCost;
                    neighbor.parent = current;

                    // Re-add to priority queue with updated cost
                    openSet.remove(neighbor);
                    openSet.add(neighbor);
                }
            }
        }

        // No path found
        return new ArrayList<>();
    }

    /**
     * Get movement directions (8-directional movement)
     */
    private int[][] getDirections() {
        return new int[][] {
                { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }, // Cardinal directions
                { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } // Diagonal directions
        };
    }

    /**
     * Check if a position is valid (within bounds, not blocked)
     */
    private boolean isValidPosition(int x, int y, Set<String> dynamicObstacles) {
        // Check bounds
        if (x < 0 || x >= rows || y < 0 || y >= columns) {
            return false;
        }

        // Check dynamic obstacles (other robots)
        if (dynamicObstacles.contains(x + "," + y)) {
            return false;
        }

        // Check static obstacles
        Cell cell = environment.getGrid()[x][y];
        if (cell != null && cell.getContent() != null) {
            // Allow movement through certain types of content (like charging zones)
            String contentType = cell.getContent().toString();
            if (contentType.contains("Obstacle")) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculate movement cost (higher for diagonal moves)
     */
    private double getMovementCost(int fromX, int fromY, int toX, int toY) {
        if (Math.abs(fromX - toX) + Math.abs(fromY - toY) == 1) {
            return 1.0; // Cardinal movement
        } else {
            return 1.414; // Diagonal movement (sqrt(2))
        }
    }

    /**
     * Heuristic function (Manhattan distance with diagonal adjustment)
     */
    private double heuristic(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x1 - x2);
        int dy = Math.abs(y1 - y2);
        return Math.max(dx, dy) + (Math.sqrt(2) - 1) * Math.min(dx, dy);
    }

    /**
     * Reconstruct path from goal to start
     */
    private List<int[]> reconstructPath(Node goalNode) {
        List<int[]> path = new ArrayList<>();
        Node current = goalNode;

        while (current != null) {
            path.add(0, new int[] { current.x, current.y });
            current = current.parent;
        }

        return path;
    }

    /**
     * Find next step towards goal with dynamic obstacle avoidance
     */
    public int[] getNextStep(int startX, int startY, int goalX, int goalY, Set<String> dynamicObstacles) {
        List<int[]> path = findPath(startX, startY, goalX, goalY, dynamicObstacles);

        if (path.size() > 1) {
            return path.get(1); // Return next step (skip current position)
        } else if (path.size() == 1) {
            return path.get(0); // Already at goal
        } else {
            return null; // No path found
        }
    }

    /**
     * Calculate total battery consumption for a given path
     * This method considers movement costs and provides accurate battery estimates
     */
    public double calculatePathBatteryCost(List<int[]> path) {
        if (path == null || path.size() <= 1) {
            return 0.0;
        }

        double totalCost = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            int[] current = path.get(i);
            int[] next = path.get(i + 1);
            totalCost += getMovementCost(current[0], current[1], next[0], next[1]);
        }

        return totalCost;
    }

    /**
     * Find path with battery constraint validation
     * Returns null if the path would consume more battery than available
     */
    public List<int[]> findBatteryAwarePath(int startX, int startY, int goalX, int goalY,
            Set<String> dynamicObstacles, int availableBattery,
            double safetyMargin) {
        List<int[]> path = findPath(startX, startY, goalX, goalY, dynamicObstacles);

        if (path.isEmpty()) {
            return path; // No path found
        }

        double pathCost = calculatePathBatteryCost(path);
        double requiredBattery = pathCost * safetyMargin;

        if (requiredBattery > availableBattery) {
            System.out.println("Path requires " + requiredBattery + " battery but only " +
                    availableBattery + " available");
            return new ArrayList<>(); // Return empty path if battery insufficient
        }

        return path;
    }

    /**
     * Find the most battery-efficient path among multiple alternatives
     */
    public List<int[]> findMostEfficientPath(int startX, int startY, int goalX, int goalY,
            Set<String> dynamicObstacles) {
        // Try multiple approaches to find the most efficient path
        List<List<int[]>> candidatePaths = new ArrayList<>();

        // Standard A* path
        candidatePaths.add(findPath(startX, startY, goalX, goalY, dynamicObstacles));

        // Try with slightly different heuristics for alternative routes
        // This could be enhanced with more sophisticated multi-path algorithms

        List<int[]> bestPath = null;
        double bestCost = Double.MAX_VALUE;

        for (List<int[]> path : candidatePaths) {
            if (!path.isEmpty()) {
                double cost = calculatePathBatteryCost(path);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestPath = path;
                }
            }
        }

        return bestPath != null ? bestPath : new ArrayList<>();
    }

    /**
     * Node class for A* algorithm
     */
    private static class Node {
        int x, y;
        double gCost, hCost, fCost;
        Node parent;

        Node(int x, int y, double gCost, double hCost) {
            this.x = x;
            this.y = y;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }

        String getKey() {
            return x + "," + y;
        }
    }
}
