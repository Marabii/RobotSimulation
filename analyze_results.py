#!/usr/bin/env python3

import json
import matplotlib.pyplot as plt
import numpy as np
from scipy.optimize import curve_fit

def load_results(filename="simulation_results.json"):
    """Load the simulation results from the JSON file."""
    with open(filename, 'r') as f:
        results = json.load(f)
    
    # Sort by number of robots
    results.sort(key=lambda x: x['num_robots'])
    return results

def plot_results(results):
    """Create detailed plots of the simulation results."""
    robots = np.array([r['num_robots'] for r in results])
    steps = np.array([r['total_steps'] for r in results])
    
    # Basic plot
    plt.figure(figsize=(12, 7))
    plt.plot(robots, steps, 'o-', linewidth=2, label='Simulation Data')
    
    # Try to fit a curve to the data
    try:
        # Function to model the relationship (inverse relationship with offset)
        def model_func(x, a, b, c):
            return a / x + b + c * x
        
        # Fit the model to the data
        params, _ = curve_fit(model_func, robots, steps)
        
        # Generate points for the fitted curve
        x_fit = np.linspace(min(robots), max(robots), 100)
        y_fit = model_func(x_fit, *params)
        
        # Plot the fitted curve
        plt.plot(x_fit, y_fit, 'r--', linewidth=2, label='Fitted Model')
        
        # Find the theoretical minimum
        # For this model, the minimum occurs at x = sqrt(a/c)
        a, b, c = params
        if c > 0:  # Only if the coefficient is positive
            optimal_robots = np.sqrt(a/c)
            if min(robots) <= optimal_robots <= max(robots):
                plt.axvline(x=optimal_robots, color='g', linestyle='--', 
                           label=f'Theoretical Optimum: {optimal_robots:.1f} robots')
    except:
        print("Could not fit a curve to the data")
    
    # Find the actual minimum from the data
    min_idx = np.argmin(steps)
    plt.axvline(x=robots[min_idx], color='b', linestyle='--', 
               label=f'Actual Minimum: {robots[min_idx]} robots')
    
    plt.xlabel('Number of Robots')
    plt.ylabel('Total Steps')
    plt.title('Package Delivery Performance vs. Number of Robots')
    plt.grid(True)
    plt.legend()
    plt.savefig('detailed_results.png')
    plt.close()
    
    # Efficiency plot (steps per robot)
    efficiency = steps / robots
    plt.figure(figsize=(12, 7))
    plt.plot(robots, efficiency, 'o-', linewidth=2)
    
    # Find the minimum efficiency
    min_eff_idx = np.argmin(efficiency)
    plt.axvline(x=robots[min_eff_idx], color='r', linestyle='--',
               label=f'Most Efficient: {robots[min_eff_idx]} robots')
    
    plt.xlabel('Number of Robots')
    plt.ylabel('Steps per Robot (lower is better)')
    plt.title('Efficiency vs. Number of Robots')
    plt.grid(True)
    plt.legend()
    plt.savefig('detailed_efficiency.png')
    plt.close()
    
    # Create a combined metric (balancing speed and efficiency)
    # This is a custom metric that you can adjust based on your priorities
    combined_metric = steps * np.sqrt(robots) / 100  # Example metric
    
    plt.figure(figsize=(12, 7))
    plt.plot(robots, combined_metric, 'o-', linewidth=2)
    
    # Find the minimum of the combined metric
    min_combined_idx = np.argmin(combined_metric)
    plt.axvline(x=robots[min_combined_idx], color='purple', linestyle='--',
               label=f'Best Balance: {robots[min_combined_idx]} robots')
    
    plt.xlabel('Number of Robots')
    plt.ylabel('Combined Metric (lower is better)')
    plt.title('Balanced Performance Metric vs. Number of Robots')
    plt.grid(True)
    plt.legend()
    plt.savefig('balanced_metric.png')
    plt.close()
    
    return robots[min_idx], robots[min_eff_idx], robots[min_combined_idx]

def analyze_results(results):
    """Analyze the results to find the optimal number of robots."""
    # Convert to numpy arrays for easier analysis
    robots = np.array([r['num_robots'] for r in results])
    steps = np.array([r['total_steps'] for r in results])
    
    # Find the fastest configuration
    min_steps_idx = np.argmin(steps)
    fastest_robots = robots[min_steps_idx]
    fastest_steps = steps[min_steps_idx]
    
    # Calculate efficiency (steps per robot)
    efficiency = steps / robots
    min_efficiency_idx = np.argmin(efficiency)
    most_efficient_robots = robots[min_efficiency_idx]
    most_efficient_steps = steps[min_efficiency_idx]
    
    # Calculate a balanced metric (example: steps * sqrt(robots))
    # This penalizes using too many robots while still valuing speed
    balanced_metric = steps * np.sqrt(robots)
    min_balanced_idx = np.argmin(balanced_metric)
    best_balanced_robots = robots[min_balanced_idx]
    best_balanced_steps = steps[min_balanced_idx]
    
    # Print the analysis
    print("\n=== Simulation Results Analysis ===")
    print(f"Total configurations tested: {len(results)}")
    print(f"Robot range tested: {min(robots)} to {max(robots)}")
    print("\nOptimal configurations:")
    print(f"1. Fastest delivery: {fastest_robots} robots, {fastest_steps} steps")
    print(f"2. Most efficient: {most_efficient_robots} robots, {most_efficient_steps} steps")
    print(f"3. Best balance: {best_balanced_robots} robots, {best_balanced_steps} steps")
    
    # Calculate improvement percentages
    baseline_robots = min(robots)
    baseline_steps = steps[np.where(robots == baseline_robots)[0][0]]
    
    speedup_vs_baseline = (baseline_steps - fastest_steps) / baseline_steps * 100
    print(f"\nSpeedup with {fastest_robots} robots vs. {baseline_robots} robot: {speedup_vs_baseline:.1f}%")
    
    # If we have enough data points, try to predict the trend
    if len(robots) >= 5:
        try:
            # Simple model: steps = a/robots + b + c*robots
            def model_func(x, a, b, c):
                return a / x + b + c * x
            
            params, _ = curve_fit(model_func, robots, steps)
            a, b, c = params
            
            print("\nModel analysis:")
            print(f"Fitted model: steps = {a:.1f}/robots + {b:.1f} + {c:.3f}*robots")
            
            if c > 0:  # Only if the coefficient is positive
                theoretical_optimum = np.sqrt(a/c)
                print(f"Theoretical optimal number of robots: {theoretical_optimum:.1f}")
                
                # Predict steps for the theoretical optimum
                predicted_steps = model_func(theoretical_optimum, a, b, c)
                print(f"Predicted steps at optimum: {predicted_steps:.1f}")
        except:
            print("\nCould not fit a predictive model to the data")
    
    return fastest_robots, most_efficient_robots, best_balanced_robots

def main():
    # Load the results
    results = load_results()
    
    if not results:
        print("No results found. Please run the simulation experiments first.")
        return
    
    # Plot the results
    fastest, most_efficient, best_balanced = plot_results(results)
    
    # Analyze the results
    analyze_results(results)
    
    print("\nRecommendation:")
    print(f"Based on the analysis, the recommended number of robots is {best_balanced}.")
    print("This provides a good balance between delivery speed and robot utilization.")
    print("\nDetailed plots have been saved to:")
    print("- detailed_results.png")
    print("- detailed_efficiency.png")
    print("- balanced_metric.png")

if __name__ == "__main__":
    main()
