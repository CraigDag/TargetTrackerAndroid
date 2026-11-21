package rangetarget.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The one JSON format used for every file core reads or writes.
 * Keys are snake_case (Kotlin `diameterMm` is written `diameter_mm`) so the
 * Python tools can use the same names.
 */
@OptIn(ExperimentalSerializationApi::class)
val RangeJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = true
    // Unknown keys fail loudly, so the two sides can't drift apart unnoticed.
    ignoreUnknownKeys = false
}
