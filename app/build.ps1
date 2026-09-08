# MapAdKiller module build (offline toolchain, libxposed API 102)
# ASCII-safe temp build; packages META-INF/xposed/* to APK root (java resources)
$ErrorActionPreference = "Stop"
$Sdk  = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$Bt   = "$Sdk\build-tools\35.0.0"
$Aj   = "$Sdk\platforms\android-34\android.jar"
$Jdk  = "D:\JDK"
$Javac= "$Jdk\bin\javac.exe"
$Keytool = "$Jdk\bin\keytool.exe"
$Ws   = "D:\pojie应用\biancheng\work\map-adhook"

$btamp = Get-Date -Format "HHmmss"
$src = "C:\Users\Administrator\AppData\Local\Temp\makb$btamp"
if (Test-Path $src) { Remove-Item $src -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$src\build\stubs","$src\build\classes","$src\build\dex","$src\build\out" | Out-Null
Copy-Item "$Ws\module\stub-src","$Ws\module\src","$Ws\module\res","$Ws\module\META-INF" -Destination $src -Recurse -Force
Copy-Item "$Ws\module\AndroidManifest.xml" $src
if (-not (Test-Path "$src\AndroidManifest.xml")) { throw "copy failed" }

Write-Host "[1/6] compile libxposed-api stubs (compile-only)"
& $Javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $Aj -d "$src\build\stubs" (Get-ChildItem "$src\stub-src" -Recurse -Filter *.java | % FullName) 2>&1 | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) { throw "stub compile failed" }

Write-Host "[2/6] compile module sources"
& $Javac -encoding UTF-8 -nowarn -source 8 -target 8 -bootclasspath $Aj -classpath "$src\build\stubs" -d "$src\build\classes" (Get-ChildItem "$src\src" -Recurse -Filter *.java | % FullName) 2>&1 | Select-Object -Last 12
if ($LASTEXITCODE -ne 0) { throw "module compile failed" }

Write-Host "[3/6] d8 -> classes.dex"
& "$Jdk\bin\jar.exe" -cf "$src\build\classes.jar" -C "$src\build\classes" .
cmd /c "`"$Bt\d8.bat`" --min-api 21 --lib `"$Aj`" --output `"$src\build\dex`" `"$src\build\classes.jar`" > `"$src\build\d8.log`" 2>&1"
Get-Content "$src\build\d8.log" -ErrorAction SilentlyContinue | Select-Object -Last 20
if (-not (Test-Path "$src\build\dex\classes.dex")) { throw "d8 failed" }

Write-Host "[4/6] aapt2 compile+link + 7z add dex/META-INF"
& "$Bt\aapt2.exe" compile --dir "$src\res" -o "$src\build\res.zip" > "$src\build\aapt2c.log" 2>&1
if (-not (Test-Path "$src\build\res.zip")) { throw "aapt2 compile failed" }
& "$Bt\aapt2.exe" link -o "$src\build\out\module.apk" --manifest "$src\AndroidManifest.xml" -I $Aj --min-sdk-version 21 --target-sdk-version 34 "$src\build\res.zip" > "$src\build\aapt2l.log" 2>&1
if (-not (Test-Path "$src\build\out\module.apk")) { Get-Content "$src\build\aapt2l.log"; throw "aapt2 link failed" }
Copy-Item "$src\build\dex\classes.dex" "$src\build\out\classes.dex"
Copy-Item "$src\META-INF" "$src\build\out\META-INF" -Recurse -Force
Push-Location "$src\build\out"
$seven = @("D:\7-Zip\7z.exe","C:\Program Files\7-Zip\7z.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($seven) {
    & $seven a -tzip module.apk classes.dex META-INF | Select-Object -Last 3
} else {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open("$src\build\out\module.apk", "Update")
    [System.IO.Compression.ZipFileExtensions]::CreateFromFilePath($zip, "$src\build\out\classes.dex", "classes.dex") | Out-Null
    Get-ChildItem "$src\build\out\META-INF" -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring("$src\build\out".Length+1).Replace("\","/")
        [System.IO.Compression.ZipFileExtensions]::CreateFromFilePath($zip, $_.FullName, $rel) | Out-Null
    }
    $zip.Dispose()
}
Pop-Location
if (-not (Test-Path "$src\build\out\module.apk")) { throw "7z add failed" }

Write-Host "[5/6] zipalign + sign v1+v2+v3"
& "$Bt\zipalign.exe" -f 4 "$src\build\out\module.apk" "$src\build\out\module-aligned.apk"
if (-not (Test-Path "$src\build\out\module-aligned.apk")) { Copy-Item "$src\build\out\module.apk" "$src\build\out\module-aligned.apk" }
$ks = "$Ws\module\debug.keystore"
if (-not (Test-Path $ks)) {
    cmd /c "`"$Keytool`" -genkeypair -keystore `"$ks`" -storepass android -keypass android -alias mapadkiller -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=MapAdKiller, OU=ENI, O=ENI, L=NA, S=NA, C=CN`" > `"$src\build\keytool.log`" 2>&1"
    if (-not (Test-Path $ks)) { Get-Content "$src\build\keytool.log"; throw "keytool failed" }
}
cmd /c "`"$Bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out `"$src\build\out\MapAdKiller.apk`" `"$src\build\out\module-aligned.apk`" > `"$src\build\sign.log`" 2>&1"
cmd /c "`"$Bt\apksigner.bat`" verify --print-certs `"$src\build\out\MapAdKiller.apk`" >> `"$src\build\sign.log`" 2>&1"
Get-Content "$src\build\sign.log" -ErrorAction SilentlyContinue | Select-Object -First 8

Write-Host "[6/6] dist"
New-Item -ItemType Directory -Force -Path "$Ws\dist" | Out-Null
Copy-Item "$src\build\out\MapAdKiller.apk" "$Ws\dist\MapAdKiller.apk" -Force
Get-Item "$Ws\dist\MapAdKiller.apk" | Select-Object FullName,Length
Write-Host "BUILD OK src=$src"
