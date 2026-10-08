class AgentscopeCli < Formula
  desc "AgentScope CLI and Runtime Host"
  homepage "https://github.com/agentscope-ai/agentscope-java"
  version "2.1.0-BETA1"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/agentscope-service-dist-v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-darwin-arm64.tar.gz"
      sha256 "4c7f2eb76729fa7c2acb45607764335dc88c3123de9973c23fb039d1b30871cf"
    end
    on_intel do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/agentscope-service-dist-v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-darwin-amd64.tar.gz"
      sha256 "996940dce0bef0fee63ff860121ae0340dada810f92e99384afe87f8359e99b5"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/agentscope-service-dist-v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-linux-arm64.tar.gz"
      sha256 "ce377a8f83a8d56d23d8906e24bddc5e69039be0aa6eda91a22c498db08150e5"
    end
    on_intel do
      url "https://github.com/agentscope-ai/agentscope-java/releases/download/agentscope-service-dist-v2.1.0-BETA1/agentscope-cli-2.1.0-BETA1-linux-amd64.tar.gz"
      sha256 "a338e94a1073d4b2316dd6c9b3b04f399ddb0465898721ad3aac93e7ea49a318"
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
