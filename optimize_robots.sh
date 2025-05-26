#!/bin/bash

# Display Java version information
echo "Java version information:"
java -version
javac -version

# Clean the bin directory
rm -rf bin
mkdir -p bin

# Compile the Java code with specific source and target versions
echo "Compiling Java code..."
javac -source 17 -target 17 -d bin -cp .:lib/* secondSimulation/simulator/*.java

# Check if compilation was successful
if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    
    # Run the optimization script
    echo "Running robot optimization..."
    python3 run_robot_optimization.py
else
    echo "Compilation failed!"
fi
