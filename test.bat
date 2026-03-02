@echo off
setlocal enabledelayedexpansion

:: ds-ftp rubric test script for windows
:: run from the parent directory that contains Sender\ and Receiver\
::
:: before running:
::   cd Sender && javac *.java && cd ../Receiver && javac *.java && cd ..
::
:: usage:
::   test.bat

set TIMEOUT_MS=1000
set PORT=40000
set PASS=0
set FAIL=0

:: create test files
echo creating test files...
fsutil file createnew tmp_empty.bin 0 >nul 2>&1
if not exist tmp_empty.bin type nul > tmp_empty.bin

:: 3KB random file
powershell -Command "$bytes = New-Object byte[] 3072; (New-Object Random).NextBytes($bytes); [IO.File]::WriteAllBytes('tmp_small.bin', $bytes)"

:: 200KB random file
powershell -Command "$bytes = New-Object byte[] 204800; (New-Object Random).NextBytes($bytes); [IO.File]::WriteAllBytes('tmp_large.bin', $bytes)"

:: 130 * 124 = 16120 bytes, forces seq number wrap past 127
powershell -Command "$bytes = New-Object byte[] 16120; (New-Object Random).NextBytes($bytes); [IO.File]::WriteAllBytes('tmp_wrap.bin', $bytes)"

echo.
echo ===== 1. PACKET FORMAT ^& PROTOCOL COMPLIANCE (20pts) =====
call :run "s&w basic small file"          tmp_small.bin  tmp_o1.bin  0
call :run "gbn w=20 seq wrap (130 pkts)"  tmp_wrap.bin   tmp_o2.bin  0  20

echo.
echo ===== 2. STOP-AND-WAIT CORRECTNESS (20pts) =====
call :run "s&w rn=0 small"                     tmp_small.bin  tmp_o3.bin  0
call :run "s&w rn=5 small (ack loss)"          tmp_small.bin  tmp_o4.bin  5
call :run "s&w empty file"                     tmp_empty.bin  tmp_o5.bin  0

echo.
echo ===== 3. GO-BACK-N IMPLEMENTATION (25pts) =====
call :run "gbn w=8 rn=0 small"       tmp_small.bin  tmp_o6.bin  0  8
call :run "gbn w=20 rn=0 large"      tmp_large.bin  tmp_o7.bin  0  20
call :run "gbn w=40 rn=0 large"      tmp_large.bin  tmp_o8.bin  0  40
call :run "gbn w=80 rn=0 large"      tmp_large.bin  tmp_o9.bin  0  80

echo.
echo ===== 4. CHAOS FACTOR HANDLING (15pts) =====
call :run "s&w rn=100 large (ack loss)"        tmp_large.bin  tmp_o10.bin  100
call :run "gbn w=20 rn=5 small (chaos combo)"  tmp_small.bin  tmp_o11.bin  5  20
call :run "gbn w=20 rn=5 large (chaos combo)"  tmp_large.bin  tmp_o12.bin  5  20

echo.
echo ===== 5. TIMEOUT ^& CRITICAL FAILURE (10pts) =====
call :run "s&w rn=5 small (timeout + recovery)"  tmp_small.bin  tmp_o13.bin  5

echo.
echo =========================================
echo   PASSED: %PASS%
echo   FAILED: %FAIL%
echo =========================================

:: cleanup
del tmp_small.bin tmp_large.bin tmp_empty.bin tmp_wrap.bin 2>nul
del tmp_o*.bin 2>nul

pause
goto :eof

:: -------------------------------------------------------
:: run a single test
:: usage: call :run "label" input output rn [window]
:: -------------------------------------------------------
:run
set "LABEL=%~1"
set "INPUT=%~2"
set "OUTPUT=%~3"
set "RN=%~4"
set "WINDOW=%~5"

set /a PORT+=2
set /a ACKPORT=PORT+1

:: start receiver in background
start /b java -cp Receiver Receiver 127.0.0.1 %ACKPORT% %PORT% %OUTPUT% %RN% >nul 2>&1

:: give receiver time to bind
timeout /t 1 /nobreak >nul

:: run sender
if "%WINDOW%"=="" (
    java -cp Sender Sender 127.0.0.1 %PORT% %ACKPORT% %INPUT% %TIMEOUT_MS% > tmp_sender_out.txt 2>&1
) else (
    java -cp Sender Sender 127.0.0.1 %PORT% %ACKPORT% %INPUT% %TIMEOUT_MS% %WINDOW% > tmp_sender_out.txt 2>&1
)

:: small delay to let receiver finish writing
timeout /t 1 /nobreak >nul

:: check for critical failure
findstr /c:"Unable to transfer" tmp_sender_out.txt >nul 2>&1
if %errorlevel%==0 (
    echo   FAIL  %LABEL% ^(critical failure^)
    set /a FAIL+=1
    del tmp_sender_out.txt 2>nul
    goto :eof
)

:: grab the time
for /f "tokens=4" %%t in ('findstr /c:"Total Transmission Time" tmp_sender_out.txt') do set "TIME=%%t"

:: compare files using fc (binary compare)
fc /b "%INPUT%" "%OUTPUT%" >nul 2>&1
if %errorlevel%==0 (
    echo   PASS  %LABEL% ^(%TIME%^)
    set /a PASS+=1
) else (
    echo   FAIL  %LABEL% ^(files differ^)
    set /a FAIL+=1
)

del tmp_sender_out.txt 2>nul
del "%OUTPUT%" 2>nul
goto :eof