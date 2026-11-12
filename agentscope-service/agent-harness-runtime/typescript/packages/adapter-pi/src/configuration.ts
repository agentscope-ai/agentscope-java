import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { InMemoryCredentialStore } from "@earendil-works/pi-ai";
import {
  TurnRejected,
  type RunConfiguration,
} from "@managed-agents/harness-runtime";

export async function createManagedModelRuntime(): Promise<ModelRuntime> {
  return ModelRuntime.create({
    credentials: new InMemoryCredentialStore(),
    modelsPath: null,
    refreshOnCreate: false,
    allowModelNetwork: false,
  });
}
/** Endpoint and credential names come only from the sealed Service configuration. */
export async function configureModel(
  runtime: ModelRuntime,
  model: RunConfiguration["model"],
  env: NodeJS.ProcessEnv = process.env,
): Promise<ModelRuntime> {
  const prior = runtime.getModel(model.provider, model.id);
  if (model.baseUrl) {
    let url: URL;
    try {
      url = new URL(model.baseUrl);
    } catch {
      throw new TurnRejected("Invalid managed model endpoint");
    }
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      !model.credentialEnv
    )
      throw new TurnRejected(
        "Model endpoint requires a credential reference and a plain HTTP(S) URL",
      );
  }
  if (
    model.api ||
    model.baseUrl ||
    model.contextWindow ||
    model.outputLimit ||
    model.reasoning !== undefined ||
    model.cost
  ) {
    const api = model.api ?? prior?.api,
      baseUrl = model.baseUrl ?? prior?.baseUrl;
    const contextWindow = model.contextWindow ?? prior?.contextWindow,
      maxTokens = model.outputLimit ?? prior?.maxTokens;
    if (
      !api ||
      !baseUrl ||
      !contextWindow ||
      !maxTokens ||
      maxTokens > contextWindow ||
      (!prior && !model.cost)
    )
      throw new TurnRejected(
        "Custom model requires API, endpoint, explicit cost and valid token limits",
      );
    runtime.registerProvider(model.provider, {
      api,
      baseUrl,
      models: [
        {
          id: model.id,
          name: prior?.name ?? model.id,
          reasoning: model.reasoning ?? prior?.reasoning ?? false,
          input: prior?.input ?? ["text"],
          cost: model.cost ?? prior!.cost,
          contextWindow,
          maxTokens,
        },
      ],
    });
  }
  if (model.credentialEnv) {
    if (!/^(MANAGED_MODEL_|PI_MODEL_)[A-Z0-9_]+$/.test(model.credentialEnv))
      throw new TurnRejected(
        "Model credential reference must use MANAGED_MODEL_ or PI_MODEL_ prefix",
      );
    const secret = env[model.credentialEnv];
    if (!secret)
      throw new TurnRejected("Managed model credential is unavailable");
    await runtime.setRuntimeApiKey(model.provider, secret);
  }
  if (!runtime.getModel(model.provider, model.id))
    throw new TurnRejected("Configured PI model is unavailable");
  const configured = runtime.getModel(model.provider, model.id)!;
  const parameters = model.parameters;
  if (model.requestTimeoutSeconds !== undefined &&
      (!Number.isInteger(model.requestTimeoutSeconds) || model.requestTimeoutSeconds < 1 || model.requestTimeoutSeconds > 3600))
    throw new TurnRejected("Invalid model request timeout");
  if (parameters?.topP !== undefined && (!Number.isFinite(parameters.topP) || parameters.topP < 0 || parameters.topP > 1))
    throw new TurnRejected("Invalid model topP");
  if (parameters?.parallelToolCalls !== undefined && typeof parameters.parallelToolCalls !== "boolean")
    throw new TurnRejected("Invalid parallel tool calls");
  if (parameters?.toolChoice !== undefined && !["auto", "none", "required"].includes(parameters.toolChoice))
    throw new TurnRejected("Invalid model tool choice");
  if (configured.api === "anthropic-messages" &&
      (parameters?.parallelToolCalls !== undefined || parameters?.toolChoice !== undefined))
    throw new TurnRejected("Tool request controls require an OpenAI API");
  if (parameters?.maxTokens !== undefined &&
      (!Number.isInteger(parameters.maxTokens) || parameters.maxTokens < 1 ||
       parameters.maxTokens > configured.maxTokens))
    throw new TurnRejected("Model maxTokens exceeds outputLimit");
  if (parameters?.reasoning !== undefined && !configured.reasoning)
    throw new TurnRejected("Model reasoning effort requires reasoning support");
  return runtime;
}
