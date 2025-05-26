#!/usr/bin/env python3

import os
import json
import subprocess
import re
import time
import matplotlib.pyplot as plt

# Try to import tqdm for progress bars, but make it optional
try:
    from tqdm import tqdm
    has_tqdm = True
except ImportError:
    has_tqdm = False
    # Define a simple replacement for tqdm
    def tqdm(iterable, *args, **kwargs):
        return iterable

# Configuration
CONFIG_FILE = "secondSimulation/parameters/configuration.ini"
RESULTS_FILE = "simulation_results.json"
MIN_ROBOTS = 1
MAX_ROBOTS = 30
JAVA_MAIN_CLASS = "simulator.MySimFactory"
CLASSPATH = "bin:lib/*"  # Using the bin directory for compiled classes

def modify_config(num_robots):
    """Modify the configuration file to set the number of robots."""
    with open(CONFIG_FILE, 'r') as f:
        lines = f.readlines()

    for i, line in enumerate(lines):
        if line.strip().startswith("robot ="):
            lines[i] = f"  robot = {num_robots}\n"

    with open(CONFIG_FILE, 'w') as f:
        f.writelines(lines)

    print(f"Configuration updated with {num_robots} robots")

def compile_java_code():
    """Compile only the secondSimulation package."""
    print("Compiling Java code...")

    # Create bin directory if it doesn't exist
    os.makedirs("bin", exist_ok=True)

    # Compile only the secondSimulation package with specific source and target versions
    compile_cmd = "javac -source 17 -target 17 -d bin -cp .:lib/* secondSimulation/simulator/*.java"
    print(f"Running: {compile_cmd}")

    compile_process = subprocess.run(compile_cmd, shell=True, capture_output=True, text=True)

    if compile_process.returncode != 0:
        print("Compilation failed!")
        print(f"Error: {compile_process.stderr}")
        return False

    print("Compilation successful!")

    # List compiled files
    class_files = subprocess.run("find bin -name \"*.class\" | sort",
                               shell=True, capture_output=True, text=True)
    print("Compiled classes:")
    print(class_files.stdout)

    return True

def run_simulation():
    """Run the simulation and return the total steps taken."""
    cmd = ["java", "-cp", CLASSPATH, JAVA_MAIN_CLASS]

    print(f"Running command: {' '.join(cmd)}")
    print(f"Working directory: {os.getcwd()}")

    try:
        # Run the simulation and capture output
        process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

        # Initialize variables to track progress
        total_steps = None
        output_lines = []

        # Process output line by line as it comes
        for line in iter(process.stdout.readline, ''):
            print(line, end='')  # Print output in real-time
            output_lines.append(line)

            # Look for the line that indicates all packages were delivered
            if "All packages delivered in" in line:
                match = re.search(r"All packages delivered in (\d+) steps", line)
                if match:
                    total_steps = int(match.group(1))

        # Wait for process to complete
        process.wait()

        # Capture any stderr output
        stderr = process.stderr.read()

        if process.returncode != 0:
            print(f"Simulation failed with return code {process.returncode}")
            print(f"Error output: {stderr}")

            # Check if we have any output at all
            if not output_lines:
                print("No output was produced. This might be a classpath issue.")
                print("Make sure the Java classes are properly compiled and in the correct directory.")
                print("Try running: javac -d bin -cp \".:lib/*\" secondSimulation/simulator/*.java")
                print("Then: java -cp bin:lib/* simulator.MySimFactory")

            return None

        # If we didn't find the total steps but the process completed successfully,
        # try to extract it from the last few lines
        if total_steps is None and output_lines:
            for line in reversed(output_lines):
                if "All packages delivered in" in line:
                    match = re.search(r"All packages delivered in (\d+) steps", line)
                    if match:
                        total_steps = int(match.group(1))
                        break

        return total_steps

    except Exception as e:
        print(f"Error running simulation: {e}")
        import traceback
        traceback.print_exc()
        return None

def plot_results(results):
    """Plot the results to visualize the optimal number of robots."""
    robots = [r['num_robots'] for r in results]
    steps = [r['total_steps'] for r in results]

    plt.figure(figsize=(10, 6))
    plt.plot(robots, steps, 'o-', linewidth=2)
    plt.xlabel('Number of Robots')
    plt.ylabel('Total Steps')
    plt.title('Package Delivery Performance vs. Number of Robots')
    plt.grid(True)
    plt.savefig('simulation_results.png')
    plt.close()

    # Also create an efficiency plot (steps per robot)
    efficiency = [s/r for s, r in zip(steps, robots)]
    plt.figure(figsize=(10, 6))
    plt.plot(robots, efficiency, 'o-', linewidth=2)
    plt.xlabel('Number of Robots')
    plt.ylabel('Steps per Robot (lower is better)')
    plt.title('Efficiency vs. Number of Robots')
    plt.grid(True)
    plt.savefig('efficiency_results.png')
    plt.close()

def main():
    results = []

    # First, compile the Java code
    if not compile_java_code():
        print("Cannot proceed with experiments due to compilation errors.")
        return

    # Check if we have existing results
    if os.path.exists(RESULTS_FILE):
        with open(RESULTS_FILE, 'r') as f:
            results = json.load(f)
        print(f"Loaded {len(results)} existing results")

    # Determine which robot counts we still need to test
    tested_robots = set(r['num_robots'] for r in results)
    robots_to_test = [n for n in range(MIN_ROBOTS, MAX_ROBOTS + 1) if n not in tested_robots]

    if not robots_to_test:
        print("All robot configurations have been tested!")
    else:
        print(f"Testing {len(robots_to_test)} robot configurations: {robots_to_test}")

    # Run simulations for each untested robot count
    for num_robots in tqdm(robots_to_test):
        print(f"\n--- Testing with {num_robots} robots ---")

        # Modify the configuration
        modify_config(num_robots)

        # Run the simulation
        total_steps = run_simulation()

        if total_steps is not None:
            # Record the result
            result = {
                'num_robots': num_robots,
                'total_steps': total_steps,
                'timestamp': time.strftime('%Y-%m-%d %H:%M:%S')
            }
            results.append(result)

            # Save results after each simulation
            with open(RESULTS_FILE, 'w') as f:
                json.dump(results, f, indent=2)

            print(f"Results saved to {RESULTS_FILE}")
        else:
            print(f"Skipping result for {num_robots} robots due to error")

    # Sort results by number of robots
    results.sort(key=lambda x: x['num_robots'])

    # Save final sorted results
    with open(RESULTS_FILE, 'w') as f:
        json.dump(results, f, indent=2)

    # Find the optimal number of robots
    if results:
        # Sort by total steps (ascending)
        sorted_by_steps = sorted(results, key=lambda x: x['total_steps'])
        fastest = sorted_by_steps[0]

        # Sort by efficiency (steps per robot, ascending)
        sorted_by_efficiency = sorted(results, key=lambda x: x['total_steps'] / x['num_robots'])
        most_efficient = sorted_by_efficiency[0]

        print("\n--- Results Summary ---")
        print(f"Fastest delivery: {fastest['num_robots']} robots, {fastest['total_steps']} steps")
        print(f"Most efficient: {most_efficient['num_robots']} robots, {most_efficient['total_steps']} steps")

        # Plot the results
        plot_results(results)
        print("Plots saved as simulation_results.png and efficiency_results.png")

if __name__ == "__main__":
    main()
