// Placeholder test: proves the toolchain, include paths and third-party headers work.
#include <gtest/gtest.h>

#include <Eigen/Core>
#include <mcap/types.hpp>
#include <sophus/se3.hpp>

#include "capturekit/version.hpp"

TEST(Placeholder, ToolchainAndHeaders) {
  const Sophus::SE3d identity;
  EXPECT_TRUE(identity.matrix().isIdentity());
  EXPECT_STREQ(capturekit::core_version(), "0.0.1");
}
