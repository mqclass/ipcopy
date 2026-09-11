$ErrorActionPreference = 'Stop'

$cwd = Get-Location
$cpList = [System.Collections.Generic.List[string]]::new()
$cpList.Add('C:\Users\winstone\AppData\Roaming\FreesmLauncher\instances\1.21.11 SM\minecraft\.fabric\remappedJars\minecraft-1.21.11-0.19.3\client-intermediary.jar')
$cpList.Add('C:\Users\winstone\AppData\Roaming\FreesmLauncher\libraries\net\fabricmc\fabric-loader\0.19.3\fabric-loader-0.19.3.jar')
$cpList.Add('C:\Users\winstone\AppData\Roaming\FreesmLauncher\libraries\net\fabricmc\sponge-mixin\0.17.3+mixin.0.8.7\sponge-mixin-0.17.3+mixin.0.8.7.jar')
$cpList.Add('C:\Users\winstone\AppData\Roaming\FreesmLauncher\libraries\com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar')

# Add Gson from libraries
Get-ChildItem -Path "C:\Users\winstone\AppData\Roaming\FreesmLauncher\libraries\com\google\code\gson" -Recurse -Filter "gson-2.11.0.jar" | ForEach-Object {
    $cpList.Add($_.FullName)
}

# Add ModMenu from mods
Get-ChildItem -Path "C:\Users\winstone\AppData\Roaming\FreesmLauncher\instances\1.21.11 SM\minecraft\mods" -Filter "modmenu*.jar" | ForEach-Object {
    $cpList.Add($_.FullName)
}

# Add all jars from processedMods
Get-ChildItem -Path "C:\Users\winstone\AppData\Roaming\FreesmLauncher\instances\1.21.11 SM\minecraft\.fabric\processedMods" -Filter "*.jar" | ForEach-Object {
    $cpList.Add($_.FullName)
}

$cp = $cpList -join ';'

$outDir = "$cwd\build\classes\java\main"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$sources = Get-ChildItem -Path "$cwd\src\main\java" -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

Write-Host "Compiling sources..."
& javac -encoding UTF-8 -proc:none -cp $cp -d $outDir $sources
if ($LASTEXITCODE -ne 0) {
    Write-Error "javac failed with exit code $LASTEXITCODE"
    exit 1
}

Write-Host "Compilation SUCCESSFUL!"

# Copy resources into build
$resourcesDir = "$cwd\src\main\resources"
Copy-Item -Recurse -Force "$resourcesDir\*" "$outDir\"

# Build Jar
$libsDir = "$cwd\build\libs"
New-Item -ItemType Directory -Force -Path $libsDir | Out-Null
$jarFile = "$libsDir\ipcopy-1.1.0.jar"
if (Test-Path $jarFile) { Remove-Item -Force $jarFile }

Write-Host "Packaging JAR: $jarFile..."
& jar -cf $jarFile -C $outDir .

if (Test-Path $jarFile) {
    $size = (Get-Item $jarFile).Length
    Write-Host "JAR BUILT SUCCESSFULLY! Size: $size bytes"
    
    $modsTarget = "C:\Users\winstone\AppData\Roaming\FreesmLauncher\instances\1.21.11 SM\minecraft\mods"
    if (Test-Path $modsTarget) {
        try {
            # Remove old versions
            Get-ChildItem -Path $modsTarget -Filter "ipcopy*.jar" | Remove-Item -Force -ErrorAction Stop
            Copy-Item -Force $jarFile "$modsTarget\ipcopy-1.1.0.jar"
            Write-Host "Deployed to FreesmLauncher mods: $modsTarget\ipcopy-1.1.0.jar"
        } catch {
            Write-Warning "Minecraft is currently running and has locked the mod JAR. The new version is built at: $jarFile`nPlease restart Minecraft to update the mod in the mods folder!"
        }
    }
} else {
    Write-Error "JAR creation failed!"
}

