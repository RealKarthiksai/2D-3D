#pragma once
// Minimal JSON helpers: a streaming writer (deterministic formatting) and a flat config
// parser for {"key": number|string|bool} objects. The core needs no general JSON reader.
#include <charconv>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <variant>

namespace capturekit::json {

inline std::string escape(std::string_view s) {
  std::string o;
  o.reserve(s.size() + 2);
  for (char c : s) {
    switch (c) {
      case '"': o += "\\\""; break;
      case '\\': o += "\\\\"; break;
      case '\n': o += "\\n"; break;
      case '\r': o += "\\r"; break;
      case '\t': o += "\\t"; break;
      default:
        if (static_cast<unsigned char>(c) < 0x20) {
          char b[8];
          std::snprintf(b, sizeof b, "\\u%04x", c);
          o += b;
        } else {
          o += c;
        }
    }
  }
  return o;
}

/// Shortest round-trip representation; NaN/Inf become null (JSON has no NaN).
inline std::string num(double v) {
  if (!std::isfinite(v)) return "null";
  char b[32];
  auto r = std::to_chars(b, b + sizeof b, v);
  std::string s(b, r.ptr);
  return s;
}
inline std::string num(int64_t v) { return std::to_string(v); }
inline std::string num(uint64_t v) { return std::to_string(v); }
inline std::string num(int v) { return std::to_string(v); }
inline std::string str(std::string_view s) { return "\"" + escape(s) + "\""; }

using FlatValue = std::variant<double, std::string, bool>;
using FlatObject = std::map<std::string, FlatValue, std::less<>>;

/// Parses a flat JSON object. Nested objects/arrays are rejected (returns nullopt).
std::optional<FlatObject> parse_flat(std::string_view text, std::string* error = nullptr);

}  // namespace capturekit::json
