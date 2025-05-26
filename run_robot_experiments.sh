#!/bin/bash

# Display Java version information
echo "Java version information:"
java -version
javac -version

# Make the Python script executable
chmod +x run_robot_experiments.py

# Clean the bin directory
rm -rf bin
mkdir -p bin

# Run the experiments
python3 run_robot_experiments.py

# If you want to analyze the results separately
# python3 analyze_results.py

echo "Experiments complete!"
