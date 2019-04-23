

# massif-wrapper OUTDATED!!! - please visit the wiki instead

## Prequisites:

You must have MATLAB installed on your computer.

## Setup:

### Step 1

Clone the repository, then get the dependencies with gradlew.

Edit the matlab-wrapper-config.json file. Te tempModelFileLocation field must be edited! The rest is optional.

### Step 2

Add MATLABROOT as an env. variable. (for example: C:\Program Files\MATLAB\R2018b)

Add MATLABROOT/bin/glnxa64/ or MATLABROOT/bin/glnxa32/ or MATLABROOT\bin\win64 or 
MATLABROOT\bin\win32 to the path (depending on your OS).

_Make sure the environmental variables got refreshed after setting them. (For example: restart console if it was running before setting the variables)_
### Step 3

Start MATLAB, add massif_scripts folder to the path: addpath('your/path/massif_scripts/')

Share the MATLAB engine: matlab.engine.shareEngine('Engine_1')

## Usage:

CD to the root and run with "gradle run".

By default the swagger UI can be reached on http://localhost:8234/static/matlabwrapper/swagger#/
