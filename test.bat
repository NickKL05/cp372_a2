@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: ds-ftp performance benchmark for cp372 assignment 2 report
::
:: runs every required configuration from the spec:
::   4 modes: s&w, gbn-20, gbn-40, gbn-80
::   2 file sizes: small (3KB), large (500KB)
::   3 reliability numbers: rn=0, rn=5, rn=100
::   3 runs each, averaged
::
:: total: 4 x 2 x 3 x 3 = 72 runs
::
:: before running:
::   cd Sender && javac *.java && cd ..\Receiver && javac *.java && cd ..
::
:: run from the parent directory containing Sender\ and Receiver\
::   benchmark.bat
::
:: output: prints the full performance table to console and
::         saves it to benchmark_results.txt
:: ============================================================

set TIMEOUT_MS=1000
set PORT=50000
set RUNS=3

:: results csv for computing averages
set CSV=benchmark_raw.csv
echo mode,file,rn,run,time > %CSV%

:: -------------------------------------------------------
:: create test files
:: -------------------------------------------------------
echo creating test files...

:: small: 3KB (< 4KB as required by spec)
powershell -Command "$bytes = New-Object byte[] 3072; (New-Object Random).NextBytes($bytes); [IO.File]::WriteAllBytes('tmp_bench_small.bin', $bytes)"

:: large: 500KB (in the 0.2-2MB range as required by spec)
powershell -Command "$bytes = New-Object byte[] 512000; (New-Object Random).NextBytes($bytes); [IO.File]::WriteAllBytes('tmp_bench_large.bin', $bytes)"

echo   small: 3,072 bytes (3 KB)
echo   large: 512,000 bytes (500 KB)
echo.

:: -------------------------------------------------------
:: run all 72 tests
:: -------------------------------------------------------

:: track total for progress indicator
set TOTAL=72
set CURRENT=0

echo running %TOTAL% tests (this will take a few minutes)...
echo s&w with rn=5 on large files is the slowest (~80+ seconds each)
echo.

:: modes: label, window (empty string = s&w)
:: we loop through them manually since batch doesn't do arrays of tuples well

for %%R in (0 5 100) do (
    for %%F in (small large) do (
        if "%%F"=="small" (set "FPATH=tmp_bench_small.bin") else (set "FPATH=tmp_bench_large.bin")

        call :run3 "S&W"    "%%F" %%R "" "!FPATH!"
        call :run3 "GBN-20" "%%F" %%R 20 "!FPATH!"
        call :run3 "GBN-40" "%%F" %%R 40 "!FPATH!"
        call :run3 "GBN-80" "%%F" %%R 80 "!FPATH!"
    )
)

echo.
echo all %TOTAL% runs complete.
echo.

:: -------------------------------------------------------
:: compute averages and print the performance table
:: -------------------------------------------------------

set OUTFILE=benchmark_results.txt

echo. > %OUTFILE%
echo DS-FTP Performance Table (averaged over %RUNS% runs) >> %OUTFILE%
echo Timeout: %TIMEOUT_MS%ms ^| Small: 3KB ^| Large: 500KB >> %OUTFILE%
echo. >> %OUTFILE%
echo -------------------------------------------------------------------------- >> %OUTFILE%
echo  Mode     ^| File   ^|  RN  ^|  Run 1   ^|  Run 2   ^|  Run 3   ^|  Average  >> %OUTFILE%
echo -------------------------------------------------------------------------- >> %OUTFILE%

echo.
echo ====================================================================
echo  DS-FTP Performance Table (averaged over %RUNS% runs)
echo  Timeout: %TIMEOUT_MS%ms ^| Small: 3KB ^| Large: 500KB
echo ====================================================================
echo.
echo  Mode     ^| File   ^|  RN  ^|  Run 1   ^|  Run 2   ^|  Run 3   ^|  Average
echo --------------------------------------------------------------------------

for %%M in (S^&W GBN-20 GBN-40 GBN-80) do (
    for %%F in (small large) do (
        for %%R in (0 5 100) do (
            call :printrow "%%M" "%%F" %%R
        )
    )
)

echo --------------------------------------------------------------------------
echo -------------------------------------------------------------------------- >> %OUTFILE%
echo.
echo results saved to %OUTFILE%
echo raw data saved to %CSV%

:: cleanup test files
del tmp_bench_small.bin tmp_bench_large.bin 2>nul
del tmp_bench_out_*.bin 2>nul
del tmp_sender_bench.txt 2>nul

pause
goto :eof

:: -------------------------------------------------------
:: :run3 - runs a single configuration 3 times
:: args: mode file rn window filepath
:: -------------------------------------------------------
:run3
set "R3_MODE=%~1"
set "R3_FILE=%~2"
set "R3_RN=%~3"
set "R3_WIN=%~4"
set "R3_PATH=%~5"

for /l %%I in (1,1,%RUNS%) do (
    set /a CURRENT+=1
    set /a PORT+=2
    set /a ACKP=PORT+1

    :: start receiver
    start /b java -cp Receiver Receiver 127.0.0.1 !ACKP! !PORT! tmp_bench_out_%%I.bin !R3_RN! >nul 2>&1
    timeout /t 1 /nobreak >nul

    :: run sender
    if "!R3_WIN!"=="" (
        java -cp Sender Sender 127.0.0.1 !PORT! !ACKP! !R3_PATH! %TIMEOUT_MS% > tmp_sender_bench.txt 2>&1
    ) else (
        java -cp Sender Sender 127.0.0.1 !PORT! !ACKP! !R3_PATH! %TIMEOUT_MS% !R3_WIN! > tmp_sender_bench.txt 2>&1
    )

    timeout /t 1 /nobreak >nul

    :: extract time
    set "BTIME=FAIL"
    for /f "tokens=4" %%T in ('findstr /c:"Total Transmission Time" tmp_sender_bench.txt 2^>nul') do set "BTIME=%%T"

    :: check for critical failure
    findstr /c:"Unable to transfer" tmp_sender_bench.txt >nul 2>&1
    if !errorlevel!==0 set "BTIME=FAIL"

    :: verify file integrity
    set "BMATCH=yes"
    fc /b "!R3_PATH!" "tmp_bench_out_%%I.bin" >nul 2>&1
    if !errorlevel! neq 0 set "BMATCH=MISMATCH"

    :: log to csv
    echo !R3_MODE!,!R3_FILE!,!R3_RN!,%%I,!BTIME! >> %CSV%

    :: progress
    echo   [!CURRENT!/%TOTAL%] !R3_MODE! ^| !R3_FILE! ^| RN=!R3_RN! ^| run %%I: !BTIME!s (!BMATCH!^)

    del tmp_bench_out_%%I.bin 2>nul
)
goto :eof

:: -------------------------------------------------------
:: :printrow - prints one row of the averaged table
:: args: mode file rn
:: -------------------------------------------------------
:printrow
set "PR_MODE=%~1"
set "PR_FILE=%~2"
set "PR_RN=%~3"

:: pull the 3 times from the csv using powershell for reliable float math
for /f "usebackq delims=" %%A in (`powershell -Command "$lines = Get-Content '%CSV%' | Where-Object { $_ -match '^%PR_MODE%,%PR_FILE%,%PR_RN%,' }; $times = $lines | ForEach-Object { ($_ -split ',')[4] }; $t1 = $times[0]; $t2 = $times[1]; $t3 = $times[2]; if ($t1 -eq 'FAIL' -or $t2 -eq 'FAIL' -or $t3 -eq 'FAIL') { Write-Output ('FAIL|FAIL|FAIL|FAIL') } else { $avg = [math]::Round(([double]$t1 + [double]$t2 + [double]$t3) / 3, 2); Write-Output ('{0}|{1}|{2}|{3}' -f $t1,$t2,$t3,$avg) }"`) do set "VALS=%%A"

for /f "tokens=1-4 delims=|" %%A in ("!VALS!") do (
    set "T1=%%A"
    set "T2=%%B"
    set "T3=%%C"
    set "AVG=%%D"
)

set "PR_MODE=!PR_MODE!        "
set "PR_FILE=!PR_FILE!      "
set "PR_RN=  !PR_RN!"

echo  !PR_MODE:~0,8! ^| !PR_FILE:~0,6! ^| !PR_RN:~-4! ^| !T1!s	 ^| !T2!s	 ^| !T3!s	 ^| !AVG!s
echo  !PR_MODE:~0,8! ^| !PR_FILE:~0,6! ^| !PR_RN:~-4! ^| !T1!s	 ^| !T2!s	 ^| !T3!s	 ^| !AVG!s >> %OUTFILE%
goto :eof