package io.agentscope.core.memory.autocontext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.StateModuleBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AutoContextMemory - Intelligent context memory management system.
 *
 * <p>AutoContextMemory implements the {@link Memory} interface and provides automated
 * context compression, offloading, and summarization to optimize LLM context window usage.
 * When conversation history exceeds configured thresholds, the system automatically applies
 * multiple compression strategies to reduce context size while preserving important information.
 *
 * <p>Key features:
 * <ul>
 *   <li>Automatic compression when message count or token count exceeds thresholds</li>
 *   <li>Five progressive compression strategies (from lightweight to heavyweight)</li>
 *   <li>Intelligent summarization using LLM models</li>
 *   <li>Content offloading to external storage</li>
 *   <li>Tool call interface preservation during compression</li>
 *   <li>Dual storage mechanism (working storage and original storage)</li>
 * </ul>
 *
 * <p>Compression strategies (applied in order):
 * <ol>
 *   <li>Compress historical tool invocations</li>
 *   <li>Offload large messages (with lastKeep protection)</li>
 *   <li>Offload large messages (without protection)</li>
 *   <li>Summarize historical conversation rounds</li>
 *   <li>Compress current round messages</li>
 * </ol>
 *
 * <p>Storage architecture:
 * <ul>
 *   <li>Working Memory Storage: Stores compressed messages for actual conversations</li>
 *   <li>Original Memory Storage: Stores complete, uncompressed message history</li>
 * </ul>
 */
public class AutoContextMemory extends StateModuleBase implements Memory {

	private static final Logger log = LoggerFactory.getLogger(AutoContextMemory.class);

	/**
	 * Working memory storage for compressed and offloaded messages.
	 * This storage is used for actual conversations and may contain compressed summaries.
	 */
	private final MemoryStorage workingMemoryStorage;

	/**
	 * Original memory storage for complete, uncompressed message history.
	 * This storage maintains the full conversation history in its original form (append-only).
	 */
	private final MemoryStorage originalMemoryStorage;

	/**
	 * Context offloader for storing large content externally.
	 * Used to offload large messages and tool invocation histories to reduce memory usage.
	 */
	private final ContextOffLoader contextOffLoader;

	/**
	 * Auto context configuration containing thresholds and settings.
	 * Defines compression triggers, storage options, and offloading behavior.
	 */
	private final AutoContextConfig autoContextConfig;

	/**
	 * LLM model used for generating summaries and compressing content.
	 * Required for intelligent compression and summarization operations.
	 */
	private Model model;

	/**
	 * Creates a new AutoContextMemory instance with the specified configuration and model.
	 *
	 * <p>Initializes the memory system with:
	 * <ul>
	 *   <li>Working memory storage (defaults to InMemoryStorage if not specified)</li>
	 *   <li>Original memory storage (defaults to InMemoryStorage if not specified)</li>
	 *   <li>Context offloader (defaults to InMemoryContextOffLoader if not specified)</li>
	 * </ul>
	 *
	 * @param autoContextConfig the configuration for auto context management
	 * @param model the LLM model to use for compression and summarization
	 */
	public AutoContextMemory(AutoContextConfig autoContextConfig, Model model) {
		this.model = model;
		this.autoContextConfig = autoContextConfig;
		contextOffLoader =
				(autoContextConfig.contextOffLoader == null)
						? new InMemoryContextOffLoader()
						: autoContextConfig.contextOffLoader;
		workingMemoryStorage =
				(autoContextConfig.contextStorage == null)
						? new InMemoryStorage()
						: autoContextConfig.contextStorage;
		originalMemoryStorage =
				(autoContextConfig.historyStorage == null)
						? new InMemoryStorage()
						: autoContextConfig.historyStorage;
	}

	@Override
	public void addMessage(Msg message) {
		workingMemoryStorage.addMessage(message);
		originalMemoryStorage.addMessage(message);
	}

	@Override
	public List<Msg> getMessages() {

		List<Msg> currentContextMessages = workingMemoryStorage.getMessages();

		boolean msgCountReached = currentContextMessages.size() >= autoContextConfig.msgThreshold;

		// 0.check msg count or token reach compress threshold
		int calculateToken = TokenCounterUtil.calculateToken(currentContextMessages);
		int thresholdToken = (int) (autoContextConfig.maxToken * autoContextConfig.tokenRatio);
		boolean tokenCounterReached =
				calculateToken >= thresholdToken;
		if (!msgCountReached && !tokenCounterReached) {
			return new ArrayList<>(workingMemoryStorage.getMessages());
		}

		log.info(
				"msg count reached threshold {}, msg count {}, token reached threshold {},token"
						+ " count {},max token limit {},threshold count {}",
				msgCountReached,
				currentContextMessages.size(),
				tokenCounterReached,
				calculateToken,
				autoContextConfig.maxToken,
				thresholdToken);

		// Strategy 1.previous round-compress tools invocation
		Pair<Integer, Integer> toolMsgIndices =
				extractPrevToolMsgsForCompress(
						currentContextMessages, autoContextConfig.getLastKeep());
		if (toolMsgIndices != null) {
			summaryToolsMessages(currentContextMessages, toolMsgIndices);
			return replaceWorkingMessage(currentContextMessages);
		}

		// Strategy 2.previous round-offloading large user/assistant(lastKeep: true)
		boolean hasOffloadedLastKeep = offloadingLargePayload(currentContextMessages, true);
		if (hasOffloadedLastKeep) {
			log.info(
					"Offloaded large payload messages, lastKeep: true, updating working memory"
							+ " storage");
			return replaceWorkingMessage(currentContextMessages);
		}

		// Strategy 3. previous round-offloading large user/assistant(lastKeep: false)
		boolean hasOffloaded = offloadingLargePayload(currentContextMessages, false);
		if (hasOffloaded) {
			log.info(
					"Offloaded large payload messages, lastKeep: false,  updating working memory"
							+ " storage");
			return replaceWorkingMessage(currentContextMessages);
		}

		// Strategy 4. previous round-summary all prev last assistant messages
		boolean hasSummarized = summaryPreviousRoundMessages(currentContextMessages);
		if (hasSummarized) {
			log.info("Summarized previous round messages, updating working memory storage");
			return replaceWorkingMessage(currentContextMessages);
		}

		// Strategy 5. current round summary
		boolean currentRoundSummarized = summaryCurrentRoundMessages(currentContextMessages);
		if (currentRoundSummarized) {
			log.info("Current Round Summarized, updating working memory storage");
			return replaceWorkingMessage(currentContextMessages);
		}
		return new ArrayList<>(workingMemoryStorage.getMessages());
	}

	private List<Msg> replaceWorkingMessage(List<Msg> newMessages) {
		workingMemoryStorage.clear();
		for (Msg msg : newMessages) {
			workingMemoryStorage.addMessage(msg);
		}
		return new ArrayList<>(workingMemoryStorage.getMessages());
	}

	/**
	 * Summarize current round of conversation messages.
	 *
	 * <p>This method is called when historical messages have been compressed and offloaded,
	 * but the context still exceeds the limit. This indicates that the current round's content
	 * is too large and needs compression.
	 *
	 * <p>Strategy:
	 * 1. Find the latest user message
	 * 2. Merge and compress all messages after it (typically tool calls and tool results,
	 *    usually no assistant message yet)
	 * 3. Preserve tool call interfaces (name, parameters)
	 * 4. Compress tool results, merging multiple results and keeping key information
	 *
	 * @param rawMessages the list of messages to process
	 * @return true if summary was actually performed, false otherwise
	 */
	private boolean summaryCurrentRoundMessages(List<Msg> rawMessages) {
		if (rawMessages == null || rawMessages.isEmpty()) {
			return false;
		}

		// Step 1: Find the latest user message
		int latestUserIndex = -1;
		for (int i = rawMessages.size() - 1; i >= 0; i--) {
			Msg msg = rawMessages.get(i);
			if (msg.getRole() == MsgRole.USER) {
				latestUserIndex = i;
				break;
			}
		}

		// If no user message found, nothing to summarize
		if (latestUserIndex < 0) {
			log.info("No user message found, skipping current round summary");
			return false;
		}

		// Step 2: Check if there are messages after the user message
		if (latestUserIndex >= rawMessages.size() - 1) {
			log.info("No messages after latest user message, skipping summary");
			return false;
		}

		// Step 3: Extract messages after the latest user message
		int startIndex = latestUserIndex + 1;
		int endIndex = rawMessages.size() - 1;

		List<Msg> messagesToCompress = new ArrayList<>();
		for (int i = startIndex; i <= endIndex; i++) {
			messagesToCompress.add(rawMessages.get(i));
		}

		log.info(
				"Compressing current round messages after user at index {}, message count {}",
				latestUserIndex,
				messagesToCompress.size());

		// Step 4: Merge and compress messages (typically tool calls and results)
		Msg compressedMsg = mergeAndCompressCurrentRoundMessages(messagesToCompress);

		// Step 5: Replace original messages with compressed one
		rawMessages.subList(startIndex, endIndex + 1).clear();
		rawMessages.add(startIndex, compressedMsg);

		log.info(
				"Replaced {} messages with 1 compressed message at index {}",
				messagesToCompress.size(),
				startIndex);
		return true;
	}

	/**
	 * Merge and compress current round messages (typically tool calls and tool results).
	 *
	 * @param messages the messages to merge and compress
	 * @return compressed message
	 */
	private Msg mergeAndCompressCurrentRoundMessages(List<Msg> messages) {
		if (messages == null || messages.isEmpty()) {
			return null;
		}

		// Collect tool information: preserve tool call interfaces, compress results
		StringBuilder toolInfo = new StringBuilder();
		toolInfo.append("<current_round_tool_calls>\n");

		String uuid = null;
		List<Msg> originalMessages = new ArrayList<>(messages);

		for (Msg msg : messages) {
			List<ContentBlock> content = msg.getContent();
			if (content != null) {
				for (ContentBlock block : content) {
					if (block instanceof ToolUseBlock toolUse) {
						// Preserve tool call interface (name, parameters)
						String toolName = toolUse.getName() != null ? toolUse.getName() : "unknown";
						String toolId = toolUse.getId() != null ? toolUse.getId() : "";
						toolInfo.append(
								String.format("Tool Call: %s (ID: %s)\n", toolName, toolId));
						if (toolUse.getInput() != null && !toolUse.getInput().isEmpty()) {
							toolInfo.append("  Parameters: ")
									.append(toolUse.getInput().toString())
									.append("\n");
						}
					}
					else if (block instanceof ToolResultBlock toolResult) {
						// Compress tool result output
						String toolName =
								toolResult.getName() != null ? toolResult.getName() : "unknown";
						String toolId = toolResult.getId() != null ? toolResult.getId() : "";
						toolInfo.append(
								String.format("Tool Result: %s (ID: %s)\n", toolName, toolId));

						List<ContentBlock> output = toolResult.getOutput();
						if (output != null && !output.isEmpty()) {
							String outputText =
									output.stream()
											.filter(b -> b instanceof TextBlock)
											.map(b -> ((TextBlock) b).getText())
											.filter(text -> text != null)
											.collect(java.util.stream.Collectors.joining("\n"));

							if (outputText.length() > 500) {
								toolInfo.append("  Result: ")
										.append(outputText.substring(0, 500))
										.append("...\n");
							}
							else {
								toolInfo.append("  Result: ").append(outputText).append("\n");
							}
						}
					}
				}
			}
		}

		toolInfo.append("</current_round_tool_calls>");

		// Offload original messages if available
		if (contextOffLoader != null) {
			uuid = UUID.randomUUID().toString();
			contextOffLoader.offload(uuid, originalMessages);
		}

		// Use model to generate a compressed summary
		String compressedSummary = generateCurrentRoundSummary(toolInfo.toString(), uuid);

		// Create a compressed message
		return Msg.builder()
				.role(MsgRole.ASSISTANT)
				.name("assistant")
				.content(TextBlock.builder().text(compressedSummary).build())
				.build();
	}

	/**
	 * Generate a compressed summary of current round messages using the model.
	 *
	 * @param toolInfo the information about tool calls and results
	 * @param offloadUuid the UUID of offloaded content (if any)
	 * @return compressed summary text
	 */
	private String generateCurrentRoundSummary(String toolInfo, String offloadUuid) {
		GenerateOptions options = GenerateOptions.builder().build();
		ReasoningContext context = new ReasoningContext("current_round_compress");

		String compressPrompt = String.format(Prompts.CURRENT_ROUND_COMPRESS_PROMPT, toolInfo);

		List<Msg> newMessages = new ArrayList<>();
		newMessages.add(
				Msg.builder()
						.role(MsgRole.USER)
						.name("user")
						.content(TextBlock.builder().text(compressPrompt).build())
						.build());

		Msg block =
				model.stream(newMessages, null, options)
						.concatMap(chunk -> processChunk(chunk, context))
						.then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
						.onErrorResume(InterruptedException.class, Mono::error)
						.block();

		String compressedText = block != null ? block.getTextContent() : toolInfo;

		String result =
				String.format(
						Prompts.COMPRESSED_CURRENT_ROUND_FORMAT,
						compressedText,
						offloadUuid != null
								? String.format(
								Prompts.COMPRESSED_CURRENT_ROUND_OFFLOAD_HINT, offloadUuid)
								: "");

		return result;
	}

	/**
	 * Summarize current round of conversation messages.
	 *
	 * @param rawMessages the list of messages to process
	 * @return true if summary was actually performed, false otherwise
	 */
	private void summaryToolsMessages(
			List<Msg> rawMessages, Pair<Integer, Integer> toolMsgIndices) {
		int startIndex = toolMsgIndices.first();
		int endIndex = toolMsgIndices.second();
		log.info(
				"compress tools invocation, start index {}, end index {}, tool msg count {}",
				startIndex,
				endIndex,
				endIndex - startIndex);

		List<Msg> toolsMsg = new ArrayList<>();
		for (int i = startIndex; i <= endIndex; i++) {
			toolsMsg.add(rawMessages.get(i));
		}
		String uuid = null;
		if (contextOffLoader != null) {
			uuid = UUID.randomUUID().toString();
			contextOffLoader.offload(uuid, toolsMsg);
		}
		Msg toolsSummary = compressToolsInvocation(toolsMsg, uuid);

		MsgUtils.replaceMsg(rawMessages, startIndex, endIndex, toolsSummary);
	}

	/**
	 * Summarize all previous rounds of conversation messages before the latest assistant.
	 *
	 * <p>This method finds the latest assistant message and summarizes all conversation rounds
	 * before it. Each round consists of messages between a user message and its corresponding
	 * assistant message (typically including tool calls/results and the assistant message itself).
	 *
	 * <p>Example transformation:
	 * Before: "user1-tools-assistant1, user2-tools-assistant2, user3-tools-assistant3, user4"
	 * After:  "user1-summary, user2-summary, user3-summary, user4"
	 * Where each summary contains the compressed information from tools and assistant of that round.
	 *
	 * <p>Strategy:
	 * 1. Find the latest assistant message (this is the current round, not to be summarized)
	 * 2. From the beginning, find all user-assistant pairs before the latest assistant
	 * 3. For each pair, summarize messages between user and assistant (including assistant message)
	 * 4. Replace those messages (including assistant) with summary (process from back to front to avoid index shifting)
	 *
	 * @param rawMessages the list of messages to process
	 * @return true if summary was actually performed, false otherwise
	 */
	private boolean summaryPreviousRoundMessages(List<Msg> rawMessages) {
		if (rawMessages == null || rawMessages.isEmpty()) {
			return false;
		}

		// Step 1: Find the latest assistant message
		int latestAssistantIndex = -1;
		for (int i = rawMessages.size() - 1; i >= 0; i--) {
			Msg msg = rawMessages.get(i);
			if (msg.getRole() == MsgRole.ASSISTANT) {
				latestAssistantIndex = i;
				break;
			}
		}

		// If no assistant message found, nothing to summarize
		if (latestAssistantIndex < 0) {
			log.info("No assistant message found, skipping summary");
			return false;
		}

		// Step 2: Find all user-assistant pairs before the latest assistant
		// We'll collect them as pairs: (userIndex, assistantIndex)
		List<Pair<Integer, Integer>> userAssistantPairs = new ArrayList<>();
		int currentUserIndex = -1;

		for (int i = 0; i < latestAssistantIndex; i++) {
			Msg msg = rawMessages.get(i);
			if (msg.getRole() == MsgRole.USER) {
				currentUserIndex = i;
			}
			else if (msg.getRole() == MsgRole.ASSISTANT && currentUserIndex >= 0) {
				// Found a user-assistant pair
				if (i - currentUserIndex != 1) {
					userAssistantPairs.add(new Pair<>(currentUserIndex, i));
				}

				currentUserIndex = -1; // Reset to find next pair
			}
		}

		// If no pairs found, nothing to summarize
		if (userAssistantPairs.isEmpty()) {
			log.info("No user-assistant pairs found before latest assistant, skipping summary");
			return false;
		}

		log.info(
				"Found {} user-assistant pairs to summarize before latest assistant at index {}",
				userAssistantPairs.size(),
				latestAssistantIndex);

		// Step 3: Process pairs from back to front to avoid index shifting issues
		boolean hasSummarized = false;
		for (int pairIdx = userAssistantPairs.size() - 1; pairIdx >= 0; pairIdx--) {
			Pair<Integer, Integer> pair = userAssistantPairs.get(pairIdx);
			int userIndex = pair.first();
			int assistantIndex = pair.second();

			// Messages to summarize: between user and assistant (inclusive of assistant)
			// This includes all messages after user (tools, etc.) and the assistant message itself
			int startIndex = userIndex + 1;
			int endIndex = assistantIndex; // Include assistant message in summary

			// If no messages between user and assistant (including assistant), skip
			if (startIndex > endIndex) {
				log.info(
						"No messages to summarize between user at index {} and assistant at index"
								+ " {}",
						userIndex,
						assistantIndex);
				continue;
			}

			List<Msg> messagesToSummarize = new ArrayList<>();
			for (int i = startIndex; i <= endIndex; i++) {
				messagesToSummarize.add(rawMessages.get(i));
			}

			log.info(
					"Summarizing round {}, start index {}, end index {} (including assistant),"
							+ " message count {}",
					pairIdx + 1,
					startIndex,
					endIndex,
					messagesToSummarize.size());

			// Step 4: Offload original messages if contextOffLoader is available
			String uuid = null;
			if (contextOffLoader != null) {
				uuid = UUID.randomUUID().toString();
				contextOffLoader.offload(uuid, messagesToSummarize);
				log.info("Offloaded messages to be summarized with uuid: {}", uuid);
			}

			// Step 5: Generate summary
			Msg summaryMsg = generateConversationSummary(messagesToSummarize, uuid);

			// Step 6: Remove the messages between user and assistant (including assistant), then
			// replace with summary
			// Since we're processing from back to front, the indices are still accurate
			// for the current pair (indices of pairs after this one have already been adjusted)

			// Remove messages from startIndex to endIndex (including assistant, from back to front
			// to avoid index shifting)
			int removedCount = endIndex - startIndex + 1;
			rawMessages.subList(startIndex, endIndex + 1).clear();

			// After removal, the position where assistant was is now: assistantIndex - removedCount
			// + 1
			// But since we removed everything including assistant, we insert summary at the
			// position after user
			int insertIndex = userIndex + 1;

			// Insert summary after user (replacing the removed messages including assistant)
			rawMessages.add(insertIndex, summaryMsg);

			log.info(
					"Removed "
							+ removedCount
							+ " messages from index "
							+ startIndex
							+ " to "
							+ endIndex
							+ " (including assistant), inserted summary at index "
							+ insertIndex
							+ " (after user at index "
							+ userIndex
							+ ")");

			hasSummarized = true;
		}

		return hasSummarized;
	}

	/**
	 * Generate a summary of conversation messages using the model.
	 *
	 * @param messages the messages to summarize
	 * @param offloadUuid the UUID of offloaded messages (if any), null otherwise
	 * @return a summary message
	 */
	private Msg generateConversationSummary(List<Msg> messages, String offloadUuid) {
		GenerateOptions options = GenerateOptions.builder().build();
		ReasoningContext context = new ReasoningContext("conversation_summary");

		String summaryContentFormat =
				Prompts.CONVERSATION_SUMMARY_FORMAT
						+ (offloadUuid != null
						? String.format(
						Prompts.CONVERSATION_SUMMARY_OFFLOAD_HINT, offloadUuid)
						: "");

		List<Msg> newMessages = new ArrayList<>();
		newMessages.add(
				Msg.builder()
						.role(MsgRole.USER)
						.name("user")
						.content(
								TextBlock.builder()
										.text(Prompts.DEFAULT_CONVERSATION_SUMMARY_PROMPT_START)
										.build())
						.build());
		newMessages.addAll(messages);
		newMessages.add(
				Msg.builder()
						.role(MsgRole.USER)
						.name("user")
						.content(
								TextBlock.builder()
										.text(Prompts.DEFAULT_CONVERSATION_SUMMARY_PROMPT_END)
										.build())
						.build());

		Msg block =
				model.stream(newMessages, null, options)
						.concatMap(chunk -> processChunk(chunk, context))
						.then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
						.onErrorResume(InterruptedException.class, Mono::error)
						.block();

		if (block != null && block.getChatUsage() != null) {
			log.info(
					"Conversation summary completed, input tokens: {}, output tokens: {}",
					block.getChatUsage().getInputTokens(),
					block.getChatUsage().getOutputTokens());
		}

		return Msg.builder()
				.role(MsgRole.ASSISTANT)
				.name("assistant")
				.content(
						TextBlock.builder()
								.text(
										String.format(
												summaryContentFormat,
												block != null ? block.getTextContent() : ""))
								.build())
				.build();
	}

	/**
	 * Offload large payload messages that exceed the threshold.
	 *
	 * <p>This method finds messages before the latest assistant response that exceed
	 * the largePayloadThreshold, offloads them to storage, and replaces them with
	 * a summary containing the first 100 characters and a hint to reload if needed.
	 *
	 * @param rawMessages the list of messages to process
	 * @param lastKeep whether to keep the last N messages (unused in current implementation)
	 * @return true if any messages were offloaded, false otherwise
	 */
	private boolean offloadingLargePayload(List<Msg> rawMessages, boolean lastKeep) {
		if (rawMessages == null || rawMessages.isEmpty()) {
			return false;
		}

		// Strategy 1: If rawMessages has less than lastKeep messages, skip
		if (rawMessages.size() < autoContextConfig.getLastKeep()) {
			return false;
		}

		// Strategy 2: Find the latest assistant message and protect it and all messages after it
		int latestAssistantIndex = -1;
		for (int i = rawMessages.size() - 1; i >= 0; i--) {
			Msg msg = rawMessages.get(i);
			if (msg.getRole() == MsgRole.ASSISTANT) {
				latestAssistantIndex = i;
				break;
			}
		}

		// Determine the search end index based on lastKeep parameter
		int searchEndIndex;
		if (lastKeep) {
			// If lastKeep is true, protect the last N messages
			int lastKeepCount = autoContextConfig.getLastKeep();
			int protectedStartIndex = Math.max(0, rawMessages.size() - lastKeepCount);

			if (latestAssistantIndex >= 0) {
				// Protect both the latest assistant and the last N messages
				// Use the earlier index to ensure both are protected
				searchEndIndex = Math.min(latestAssistantIndex, protectedStartIndex);
			}
			else {
				// No assistant found, protect the last N messages
				searchEndIndex = protectedStartIndex;
			}
		}
		else {
			// If lastKeep is false, only protect up to the latest assistant (if found)
			searchEndIndex = (latestAssistantIndex >= 0) ? latestAssistantIndex : 0;
		}

		boolean hasOffloaded = false;
		long threshold = autoContextConfig.largePayloadThreshold;

		// Process messages from the beginning up to the search end index
		// Process in reverse order to avoid index shifting issues when replacing
		for (int i = searchEndIndex - 1; i >= 0; i--) {
			Msg msg = rawMessages.get(i);
			String textContent = msg.getTextContent();

			// Check if message content exceeds threshold
			if (textContent != null && textContent.length() > threshold) {
				// Offload the original message
				String uuid = UUID.randomUUID().toString();
				if (contextOffLoader != null) {
					List<Msg> offloadMsg = new ArrayList<>();
					offloadMsg.add(msg);
					contextOffLoader.offload(uuid, offloadMsg);
					log.info(
							"Offloaded large payload message at index "
									+ i
									+ ", size: "
									+ textContent.length()
									+ " chars, uuid: "
									+ uuid);
				}

				// Create replacement message with first 100 characters and offload hint
				String preview =
						textContent.length() > autoContextConfig.offloadSinglePreview
								? textContent.substring(0, autoContextConfig.offloadSinglePreview)
								+ "..."
								: textContent;

				String offloadHint = String.format(Prompts.OFFLOAD_HINT_FORMAT, preview, uuid);

				// Create replacement message preserving original role and name
				Msg replacementMsg =
						Msg.builder()
								.role(msg.getRole())
								.name(msg.getName())
								.content(TextBlock.builder().text(offloadHint).build())
								.build();

				// Replace the original message
				rawMessages.set(i, replacementMsg);
				hasOffloaded = true;
			}
		}

		return hasOffloaded;
	}

	@Override
	public void deleteMessage(int index) {
		workingMemoryStorage.deleteMessage(index);
	}

	/**
	 * Extract tool messages from raw messages for compression.
	 *
	 * <p>This method finds consecutive tool invocation messages in historical conversations
	 * that can be compressed. It searches for sequences of more than 4 consecutive tool messages
	 * before the latest assistant message.
	 *
	 * <p>Strategy:
	 * 1. If rawMessages has less than lastKeep messages, return null
	 * 2. Find the latest assistant message and protect it and all messages after it
	 * 3. Search from the beginning for the oldest consecutive tool messages (more than 4 consecutive)
	 *    that can be compressed
	 * 4. If no assistant message is found, protect the last N messages (lastKeep)
	 *
	 * @param rawMessages all raw messages
	 * @param lastKeep number of recent messages to keep uncompressed
	 * @return Pair containing startIndex and endIndex (inclusive) of compressible tool messages, or null if none found
	 */
	private Pair<Integer, Integer> extractPrevToolMsgsForCompress(
			List<Msg> rawMessages, int lastKeep) {
		if (rawMessages == null || rawMessages.isEmpty()) {
			return null;
		}

		int totalSize = rawMessages.size();

		// Step 1: If rawMessages has less than 20 messages, return null
		if (totalSize < lastKeep) {
			return null;
		}

		// Step 2: Find the latest assistant message and protect it and all messages after it
		int latestAssistantIndex = -1;
		for (int i = totalSize - 1; i >= 0; i--) {
			Msg msg = rawMessages.get(i);
			if (msg.getRole() == MsgRole.ASSISTANT) {
				latestAssistantIndex = i;
				break;
			}
		}

		// Determine the search boundary: we can only search messages before the latest assistant
		int searchEndIndex =
				(latestAssistantIndex >= 0) ? latestAssistantIndex : (totalSize - lastKeep);

		// Step 3: Find the oldest consecutive tool messages (more than 4 consecutive)
		// Search from the beginning (oldest messages first) until we find a sequence
		int consecutiveCount = 0;
		int startIndex = -1;
		int endIndex = -1;

		for (int i = 0; i < searchEndIndex; i++) {
			Msg msg = rawMessages.get(i);
			if (isToolMessage(msg)) {
				if (consecutiveCount == 0) {
					startIndex = i;
				}
				consecutiveCount++;
			}
			else {
				// If we found enough consecutive tool messages, return their indices
				if (consecutiveCount > autoContextConfig.minConsecutiveToolMessages) {
					endIndex = i - 1; // endIndex is inclusive
					return new Pair<>(startIndex, endIndex);
				}
				// Reset counter if sequence is broken
				consecutiveCount = 0;
				startIndex = -1;
			}
		}

		// Check if there's a sequence at the end of the search range
		if (consecutiveCount > autoContextConfig.minConsecutiveToolMessages) {
			endIndex = searchEndIndex - 1; // endIndex is inclusive
			return new Pair<>(startIndex, endIndex);
		}

		return null;
	}

	/**
	 * Check if a message is a tool-related message (tool use or tool result).
	 *
	 * @param msg the message to check
	 * @return true if the message contains tool use or tool result blocks, or has TOOL role
	 */
	private boolean isToolMessage(Msg msg) {
		if (msg == null) {
			return false;
		}
		// Check if message has TOOL role
		if (msg.getRole() == MsgRole.TOOL) {
			return true;
		}
		// Check if message contains ToolUseBlock or ToolResultBlock
		return msg.hasContentBlocks(ToolUseBlock.class)
				|| msg.hasContentBlocks(ToolResultBlock.class);
	}

	/**
	 * compress tools invocation.
	 *
	 * @param messages tools msg to compress.
	 * @return
	 */
	private Msg compressToolsInvocation(List<Msg> messages, String offloadUUid) {

		GenerateOptions options = GenerateOptions.builder().build();
		ReasoningContext context = new ReasoningContext("tool_compress");
		String compressContentFormat =
				Prompts.COMPRESSED_HISTORY_FORMAT
						+ ((offloadUUid != null)
						? String.format(
						Prompts.COMPRESSED_HISTORY_OFFLOAD_HINT, offloadUUid)
						: "");
		List<Msg> newMessages = new ArrayList<>();
		newMessages.add(
				Msg.builder()
						.role(MsgRole.USER)
						.name("user")
						.content(
								TextBlock.builder()
										.text(Prompts.DEFAULT_TOOL_COMPRESS_PROMPT_START)
										.build())
						.build());
		newMessages.addAll(messages);
		newMessages.add(
				Msg.builder()
						.role(MsgRole.USER)
						.name("user")
						.content(
								TextBlock.builder()
										.text(Prompts.DEFAULT_TOOL_COMPRESS_PROMPT_END)
										.build())
						.build());
		Msg block =
				model.stream(newMessages, null, options)
						.concatMap(chunk -> processChunk(chunk, context))
						.then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
						.onErrorResume(InterruptedException.class, Mono::error)
						.block();
		if (block != null && block.getChatUsage() != null) {
			log.info(
					"Tool compression completed, input tokens: {}, output tokens: {}",
					block.getChatUsage().getInputTokens(),
					block.getChatUsage().getOutputTokens());
		}
		return Msg.builder()
				.role(MsgRole.ASSISTANT)
				.name("assistant")
				.content(
						TextBlock.builder()
								.text(
										String.format(
												compressContentFormat,
												block != null ? block.getTextContent() : ""))
								.build())
				.build();
	}

	private Mono<Msg> processChunk(ChatResponse chunk, ReasoningContext context) {
		return Mono.just(chunk).doOnNext(context::processChunk).then(Mono.empty());
	}

	@Override
	public void clear() {
		workingMemoryStorage.clear();
		originalMemoryStorage.clear();
	}
}
