#!/bin/bash

# Clean the bin directory
rm -rf bin
mkdir -p bin

# Compile with specific source and target versions
echo "Compiling with Java 17 compatibility..."
javac -source 17 -target 17 -d bin -cp .:lib/* secondSimulation/simulator/*.java

# Check if compilation was successful
if [ $? -eq 0 ]; then
    echo "Compilation successful!"
    
    # List the compiled classes
    echo "Compiled classes:"
    find bin -name "*.class" | sort
    
    # Try to run the main class
    echo -e "\nTrying to run the main class..."
    java -cp bin:lib/* simulator.MySimFactory
else
    echo "Compilation failed!"
fi
