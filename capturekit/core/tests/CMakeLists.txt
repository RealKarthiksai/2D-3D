include(FetchContent)
FetchContent_Declare(googletest
  URL https://github.com/google/googletest/archive/refs/tags/v1.18.0.tar.gz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(capturekit_core_tests placeholder_test.cpp)
target_link_libraries(capturekit_core_tests PRIVATE capturekit::core GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(capturekit_core_tests)
