@echo off

if "%1"=="" GOTO ERROR

@set ENTROPY=%1
@set KANZI_JAR=e:\temp\kanzi.jar
@REM set CORPUS=c:\temp\calgary
@set CORPUS=c:\temp\silesia
@echo %ENTROPY%
@del *.tmp 2> NUL
@del *.knz 2> NUL

@REM set sources=bib book1 book2 geo news obj1 obj2 paper1 paper2 paper3 paper4 paper5 paper6 pic progc progl progp trans
@set sources=dickens mozilla mr nci ooffice osdb reymont samba sao webster x-ray xml

for %%f in (%sources%) do (
  java -jar %KANZI_JAR% -overwrite -block=4000000 -entropy=%ENTROPY% -input=%CORPUS%\%%f
)

@dir %CORPUS%\*.knz

goto END

:ERROR
echo Missing Entropy argument.

:END