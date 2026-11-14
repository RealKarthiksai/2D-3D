target_sources(capturekit_core PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/params.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/live_map.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/coverage.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/nav_grid.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/planner.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/instruction.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/guide_log.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/engine.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/guide_sink.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/sim.cpp)
