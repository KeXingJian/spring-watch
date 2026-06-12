@echo off
setlocal

set AGENT_JAR=%~dp0target\opentelemetry-javaagent.jar
set APP_JAR=%~dp0target\mock-test-1.0.0.jar
set APP_PORT=8081
set METRICS_PORT=9464

if not exist "%AGENT_JAR%" (
    echo [ERROR] %AGENT_JAR% not found. Run 'mvn package' first to copy OTel agent jar.
    exit /b 1
)
if not exist "%APP_JAR%" (
    echo [ERROR] %APP_JAR% not found. Run 'mvn package' first.
    exit /b 1
)

set "JAVA_EXE=java"
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java"
)

echo [INFO] ===========================================
echo [INFO] mock-test with OTel Java agent (full instrumentation)
echo [INFO] Java:      %JAVA_EXE%
echo [INFO] Agent:     %AGENT_JAR%
echo [INFO] App:       %APP_JAR%
echo [INFO] App port:  %APP_PORT%
echo [INFO] Metrics:   :%METRICS_PORT%/metrics
echo [INFO] ===========================================

"%JAVA_EXE%" -javaagent:"%AGENT_JAR%" ^
  -DOTEL_SERVICE_NAME=mock-test ^
  -DOTEL_RESOURCE_ATTRIBUTES=service.name=mock-test,service.namespace=spring-watch,deployment.environment=test ^
  -DOTEL_METRICS_EXPORTER=prometheus ^
  -DOTEL_LOGS_EXPORTER=none ^
  -DOTEL_TRACES_EXPORTER=none ^
  -DOTEL_EXPORTER_PROMETHEUS_HOST=0.0.0.0 ^
  -DOTEL_EXPORTER_PROMETHEUS_PORT=%METRICS_PORT% ^
  -DOTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_HTTP_SERVER_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_SERVLET_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_SPRING_WEB_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_SPRING_WEBMVC_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_TOMCAT_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_JDBC_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_HIKARI_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_ANNOTATIONS_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_CODE_FUNCTION_METRICS_ENABLED=true ^
  -DOTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED=true ^
  -Dserver.port=%APP_PORT% ^
  -jar "%APP_JAR%"

endlocal
