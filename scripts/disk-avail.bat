@echo off
SETLOCAL

FOR /F "usebackq tokens=1,2" %%f IN (`PowerShell -NoProfile -EncodedCommand "CgBnAHcAbQBpACAAVwBpAG4AMwAyAF8ATABvAGcAaQBjAGEAbABEAGkAcwBrACAALQBGAGkAbAB0AGUAcgAgACIAQwBhAHAAdABpAG8AbgA9ACcAQwA6ACcAIgB8ACUAewAkAGcAPQAxADAANwAzADcANAAxADgAMgA0ADsAWwBpAG4AdABdACQAZgA9ACgAJABfAC4ARgByAGUAZQBTAHAAYQBjAGUALwAkAGcAKQA7AFsAaQBuAHQAXQAkAHQAPQAoACQAXwAuAFMAaQB6AGUALwAkAGcAKQA7AFcAcgBpAHQAZQAtAEgAbwBzAHQAIAAoACQAdAAtACQAZgApACwAJABmAH0ACgA="`) DO ((SET U=%%f)&(SET F=%%g))

@ECHO %F%