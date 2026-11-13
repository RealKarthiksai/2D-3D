// _ckcore: Python bindings for capturekit_core. One file per core module (bind_*.cpp).
#include <nanobind/nanobind.h>

#include "capturekit/version.hpp"

namespace nb = nanobind;

void bind_bundle(nb::module_& m);

NB_MODULE(_ckcore, m) {
  m.doc() = "CaptureKit core (C++20) bindings";
  m.def("core_version", [] { return std::string(capturekit::core_version()); });
  bind_bundle(m);
}
