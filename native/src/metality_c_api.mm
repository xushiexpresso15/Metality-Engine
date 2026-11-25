#include "metality/metality_c_api.h"

extern "C" {

METALITY_API const char* metality_native_version_string(void) {
    return "Metality Native Core 0.1.0-beta.1 (Apple Silicon Metal 3/4)";
}

} // extern "C"
