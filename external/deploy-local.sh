#!/bin/bash

#######################################
# Local Development Deployment Script
# For External API Application
#
# Usage:
#   ./deploy-local.sh           # Normal mode
#   ./deploy-local.sh --debug   # Debug mode with remote debugging
#   ./deploy-local.sh --verbose # Verbose logging
#######################################

set -e  # Exit on error

# Parse arguments
DEBUG_MODE=false
VERBOSE_MODE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --debug|-d)
            DEBUG_MODE=true
            shift
            ;;
        --verbose|-v)
            VERBOSE_MODE=true
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --debug, -d     Enable remote debugging on port 5005"
            echo "  --verbose, -v   Enable verbose logging (DEBUG level)"
            echo "  --help, -h      Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0              # Normal mode"
            echo "  $0 --debug      # Debug mode"
            echo "  $0 --verbose    # Verbose logging"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_debug() {
    if [ "$VERBOSE_MODE" = true ]; then
        echo -e "${MAGENTA}��� $1${NC}"
    fi
}

# Function to print section headers
print_header() {
    echo ""
    echo -e "${BLUE}════════════════════════════════════════${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}════════════════════════════════════════${NC}"
}

# Start
clear
if [ "$DEBUG_MODE" = true ]; then
    print_header "��� Starting Local Deployment (DEBUG MODE)"
else
    print_header "��� Starting Local Deployment"
fi

# Step 1: Navigate to correct directory
print_info "Navigating to project directory..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
print_success "Current directory: $(pwd)"

# Step 2: Check if .env.dev exists
print_info "Checking for .env.dev file..."
if [ ! -f ".env.dev" ]; then
    print_error ".env.dev file not found!"
    print_info "Please create .env.dev file with required environment variables"
    exit 1
fi
print_success ".env.dev file found"

# Step 3: Clean environment variables
print_header "��� Cleaning Environment Variables"
print_info "Unsetting existing environment variables..."

# List of variable prefixes to clear
PREFIXES="POSTGRES CONTAINER SERVER JWT ORANGE SLACK EMAIL APP_ SELENIUM CHROME CODE EXPIRY MAX_ATTEMPT LIMIT SENDER SESSION SMS CANAL"

for prefix in $PREFIXES; do
    for var in $(env | grep -E "^${prefix}" | cut -d= -f1 2>/dev/null || true); do
        unset $var
        print_debug "Unset: $var"
    done
done

print_success "Environment cleaned"

# Step 4: Load environment variables from .env.dev
print_header "��� Loading Environment Variables"
print_info "Loading variables from .env.dev..."

set -a  # Automatically export all variables
source .env.dev
set +a

print_success "Environment variables loaded"

# Step 5: Verify critical variables
print_header "��� Verifying Configuration"

# Function to check variable
check_var() {
    local var_name=$1
    local var_value=${!var_name}
    
    if [ -z "$var_value" ]; then
        print_warning "$var_name is not set"
        return 1
    else
        # Mask sensitive values
        if [[ "$var_name" == *"PASSWORD"* ]] || [[ "$var_name" == *"SECRET"* ]] || [[ "$var_name" == *"CREDENTIALS"* ]]; then
            print_success "$var_name: ${var_value:0:5}***"
        else
            print_success "$var_name: $var_value"
        fi
        return 0
    fi
}

# Check critical variables
CRITICAL_VARS=(
    "POSTGRES_HOST"
    "POSTGRES_PORT"
    "POSTGRES_DATABASE"
    "POSTGRES_USERNAME"
    "POSTGRES_PASSWORD"
    "JWT_SECRET"
    "SERVER_PORT"
)

MISSING_VARS=0
for var in "${CRITICAL_VARS[@]}"; do
    if ! check_var "$var"; then
        MISSING_VARS=$((MISSING_VARS + 1))
    fi
done

if [ $MISSING_VARS -gt 0 ]; then
    print_error "$MISSING_VARS critical variables are missing"
    print_info "Please update your .env.dev file"
    exit 1
fi

# Step 6: Clean and compile
print_header "��� Building Application"
print_info "Running Maven clean..."

if mvn clean -q; then
    print_success "Maven clean completed"
else
    print_error "Maven clean failed"
    exit 1
fi

# Step 7: Run the application
print_header "��� Starting Application"

# Build Maven command based on mode
MVN_ARGS="spring-boot:run"
JVM_ARGS=""
SPRING_ARGS=""

if [ "$DEBUG_MODE" = true ]; then
    print_info "Starting Spring Boot application with remote debugging..."
    print_info "Debug Port: 5005"
    JVM_ARGS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=*:5005"
    print_warning ""
    print_warning "To attach debugger:"
    print_warning "  IntelliJ IDEA:"
    print_warning "    1. Run > Edit Configurations"
    print_warning "    2. Add New Configuration > Remote JVM Debug"
    print_warning "    3. Set Host: localhost, Port: 5005"
    print_warning "    4. Click Debug"
    print_warning ""
    print_warning "  VS Code:"
    print_warning "    1. Add to launch.json:"
    print_warning "       {"
    print_warning "         \"type\": \"java\","
    print_warning "         \"name\": \"Attach to Remote\","
    print_warning "         \"request\": \"attach\","
    print_warning "         \"hostName\": \"localhost\","
    print_warning "         \"port\": 5005"
    print_warning "       }"
    print_warning ""
else
    print_info "Starting Spring Boot application..."
fi

if [ "$VERBOSE_MODE" = true ]; then
    SPRING_ARGS="--logging.level.root=DEBUG --logging.level.crg.api.external=TRACE"
    print_info "Verbose logging enabled (DEBUG level)"
fi

print_info "Application will be available at: http://localhost:${SERVER_PORT:-8090}"
print_info "Swagger UI: http://localhost:${SERVER_PORT:-8090}/swagger-ui.html"
print_info "Actuator Health: http://localhost:${SERVER_PORT:-8090}/actuator/health"
print_info ""
print_info "Press Ctrl+C to stop the application"
echo ""

# Build and run command
if [ ! -z "$JVM_ARGS" ]; then
    MVN_ARGS="$MVN_ARGS -Dspring-boot.run.jvmArguments=\"$JVM_ARGS\""
fi

if [ ! -z "$SPRING_ARGS" ]; then
    MVN_ARGS="$MVN_ARGS -Dspring-boot.run.arguments=\"$SPRING_ARGS\""
fi

# Execute
eval "mvn $MVN_ARGS"

