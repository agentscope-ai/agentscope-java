# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

class AgentscopeCli < Formula
  desc "AgentScope CLI and Runtime Host"
  homepage "https://github.com/agentscope-ai/agentscope-java"
  version "2.1.0-BETA1"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-darwin-arm64.tar.gz"
      sha256 "a3477f58563b1457edf217a81840235ab823291cc8a62841d6fa5974b514eba3"
    end
    on_intel do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-darwin-amd64.tar.gz"
      sha256 "f435f119bf8428d8ca431e6385694c0c1fb4425659fc48b0df1b03aa6c447226"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-linux-arm64.tar.gz"
      sha256 "5e07257a0817d002745d6326b8991eee010c010cf14adac4374bb2d1b049dc66"
    end
    on_intel do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-linux-amd64.tar.gz"
      sha256 "18b68a584abaec1b9b4072b075e294bfb06f926b0abb7e8f5507a0b2a33dc4e0"
    end
  end

  def install
    bin.install "as", "agentscope-runtime-host"
    pkgshare.install "README.md"
  end

  test do
    assert_match "as version 2.1.0-BETA1", shell_output("#{bin}/as version")
    assert_match "-control-plane", shell_output("#{bin}/agentscope-runtime-host -help 2>&1")
  end
end
