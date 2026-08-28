import { useEffect, useState } from "react";
import { formatMetric } from "./state.js";
import { formatExecutionEnvironmentSnapshot, normalizeOptimizationSuggestionThresholds, normalizeQualityEvaluationRun, normalizeQualityRules, normalizeQualitySnapshot, normalizeSkillExecutionRecord } from "./quality.js";
import { normalizeQualityBenchmarks } from "./qualityDetail.js";
import { ReleaseControlPanel } from "./ReleaseControlPanel.jsx";

function statusLabel(status) {
  return { QUEUED: "排队中", RUNNING: "评测中", COMPLETED: "已完成", FAILED: "失败", TIMED_OUT: "已超时", CANCELLED: "已取消" }[status] || status || "未知";
}

function providerReadinessReason(reason, description) {
  if (description) return description;
  return {
    EXTERNAL_PROVIDERS_CONTRACT_ONLY: "外部 Provider 尚未启用真实适配器",
    ACTIVE_PROVIDER_NOT_CONFIGURED: "存在未配置的活动 Provider",
    ALL_PROVIDERS_READY: "所有 Provider 已就绪",
  }[reason] || "请检查 Provider 配置";
}

const suggestionActions = {
  OPEN: [["ACKNOWLEDGED", "已确认"], ["DISMISSED", "已忽略"]],
  ACKNOWLEDGED: [["RESOLVED", "已解决"], ["DISMISSED", "已忽略"]],
  DISMISSED: [["OPEN", "重新打开"]],
  RESOLVED: [["OPEN", "重新打开"]],
};

export function QualityCenterView({ api, role, initialEnvironmentFilters = {}, initialSkillId = "eox-query", initialSkillVersion = "1.2.0" }) {
  const navigationContext = typeof window !== "undefined" ? (window.__skillQualityNavigationContext || {}) : {};
  const contextEnvironmentFilters = navigationContext.environmentFilters || {};
  const [skillId, setSkillId] = useState(initialSkillId !== "eox-query" ? initialSkillId : (navigationContext.skillId || initialSkillId || "eox-query"));
  const [skillVersion, setSkillVersion] = useState(initialSkillVersion !== "1.2.0" ? initialSkillVersion : (navigationContext.skillVersion || initialSkillVersion || "1.2.0"));
  const [scenario, setScenario] = useState("success");
  const [runtimeId, setRuntimeId] = useState(initialEnvironmentFilters.runtimeId || contextEnvironmentFilters.runtimeId || "");
  const [mcpServerId, setMcpServerId] = useState(initialEnvironmentFilters.mcpServerId || contextEnvironmentFilters.mcpServerId || "");
  const [llmProviderId, setLlmProviderId] = useState(initialEnvironmentFilters.llmProviderId || contextEnvironmentFilters.llmProviderId || "");
  const [suiteId, setSuiteId] = useState("smoke");
  const [suiteVersion, setSuiteVersion] = useState("smoke-v1");
  const [suites, setSuites] = useState([]);
  const [rules, setRules] = useState(() => normalizeQualityRules(null));
  const [thresholds, setThresholds] = useState(() => normalizeOptimizationSuggestionThresholds(null));
  const [thresholdSaving, setThresholdSaving] = useState(false);
  const [snapshot, setSnapshot] = useState(() => normalizeQualitySnapshot(null));
  const [caseResults, setCaseResults] = useState([]);
  const [showSuiteForm, setShowSuiteForm] = useState(false);
  const [newSuiteId, setNewSuiteId] = useState("");
  const [newSuiteName, setNewSuiteName] = useState("");
  const [newSuiteVersion, setNewSuiteVersion] = useState("");
  const [newSuiteCases, setNewSuiteCases] = useState([{ id: "case-1", name: "默认成功路径" }]);
  const [runs, setRuns] = useState([]);
  const [compatibilityMatrices, setCompatibilityMatrices] = useState([]);
  const [activeCompatibilityMatrix, setActiveCompatibilityMatrix] = useState(null);
  const [compatibilityMatrixCases, setCompatibilityMatrixCases] = useState([]);
  const [compatibilityMatrixPolicy, setCompatibilityMatrixPolicy] = useState("ALL_MUST_PASS");
  const [compatibilityMatrixReleaseGate, setCompatibilityMatrixReleaseGate] = useState(false);
  const [compatibilityMatrixLoading, setCompatibilityMatrixLoading] = useState(false);
  const [matrixRuntimeIds, setMatrixRuntimeIds] = useState(() => (initialEnvironmentFilters.runtimeId || contextEnvironmentFilters.runtimeId || "").split(",").map((value) => value.trim()).filter(Boolean));
  const [matrixMcpServerIds, setMatrixMcpServerIds] = useState(() => (initialEnvironmentFilters.mcpServerId || contextEnvironmentFilters.mcpServerId || "").split(",").map((value) => value.trim()).filter(Boolean));
  const [matrixLlmProviderIds, setMatrixLlmProviderIds] = useState(() => (initialEnvironmentFilters.llmProviderId || contextEnvironmentFilters.llmProviderId || "").split(",").map((value) => value.trim()).filter(Boolean));
  const [providers, setProviders] = useState([]);
  const [providerReadiness, setProviderReadiness] = useState(null);
  const [packageSecurityReadiness, setPackageSecurityReadiness] = useState(null);
  const [providerProbes, setProviderProbes] = useState([]);
  const [providerProbeLoading, setProviderProbeLoading] = useState(false);
  const [providerContracts, setProviderContracts] = useState([]);
  const [providerContractVerification, setProviderContractVerification] = useState([]);
  const [executionEnvironments, setExecutionEnvironments] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [workItems, setWorkItems] = useState([]);
  const [optimizationExperiments, setOptimizationExperiments] = useState([]);
  const [optimizationObservations, setOptimizationObservations] = useState({});
  const [optimizationAssessments, setOptimizationAssessments] = useState({});
  const [workItemSuggestionId, setWorkItemSuggestionId] = useState("");
  const [workItemHypothesis, setWorkItemHypothesis] = useState("");
  const [workItemCandidateVersion, setWorkItemCandidateVersion] = useState(initialSkillVersion || "");
  const [workItemEvidenceType, setWorkItemEvidenceType] = useState("BENCHMARK");
  const [workItemEvidenceId, setWorkItemEvidenceId] = useState("");
  const [workItemEvidenceOutcome, setWorkItemEvidenceOutcome] = useState("");
  const [workItemOutcome, setWorkItemOutcome] = useState("");
  const [workItemLoadingId, setWorkItemLoadingId] = useState("");
  const [benchmarks, setBenchmarks] = useState([]);
  const [executions, setExecutions] = useState([]);
  const [executionScenario, setExecutionScenario] = useState("success");
  const [executionTimeoutMs, setExecutionTimeoutMs] = useState(1000);
  const [executionLoading, setExecutionLoading] = useState(false);
  const [benchmarkBaseline, setBenchmarkBaseline] = useState("1.1.0");
  const [benchmarkWindow, setBenchmarkWindow] = useState("24h");
  const [benchmarkLoading, setBenchmarkLoading] = useState(false);
  const [activeRun, setActiveRun] = useState(null);
  const [cancelLoading, setCancelLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [suggestionLoadingId, setSuggestionLoadingId] = useState("");

  const matrixCombinationCount = Math.max(1, matrixRuntimeIds.length)
    * Math.max(1, matrixMcpServerIds.length) * Math.max(1, matrixLlmProviderIds.length);
  const matrixOverLimit = matrixCombinationCount > 100;
  const matrixCatalogUnavailable = executionEnvironments.length === 0;
  const suiteSelectionAvailable = suites.some((suite) => suite.id === suiteId && suite.version === suiteVersion);

  useEffect(() => {
    if (typeof window !== "undefined" && window.__skillQualityNavigationContext) delete window.__skillQualityNavigationContext;
  }, []);

  useEffect(() => {
    setActiveRun(null);
    setSnapshot(normalizeQualitySnapshot(null));
    setCaseResults([]);
  }, [skillId, skillVersion, runtimeId, mcpServerId, llmProviderId]);

  const environmentParams = { dataSource: "mock", runtimeId, mcpServerId, llmProviderId };
  const suiteContextParams = { ...environmentParams, suiteId, suiteVersion };
  const loadRuns = () => Promise.all([api.listQualityEvaluations(skillId, environmentParams), api.listQualitySuites(), api.getQualityRules(), api.listQualityProviderContracts(), api.getQualityProviderContractVerification ? api.getQualityProviderContractVerification() : Promise.resolve({ data: [] }), api.listQualityProviders ? api.listQualityProviders() : Promise.resolve({ data: [] }), api.getQualityProviderReadiness ? api.getQualityProviderReadiness() : Promise.resolve({ data: null }), api.getOptimizationSuggestionThresholds ? api.getOptimizationSuggestionThresholds() : Promise.resolve({ data: null }), api.listQualityBenchmarks ? api.listQualityBenchmarks(skillId, suiteContextParams) : Promise.resolve({ data: [] }), api.getSkillQualitySuggestions ? api.getSkillQualitySuggestions(skillId, { version: skillVersion, window: "24h", ...environmentParams }) : Promise.resolve({ data: [] }), api.listSkillExecutions ? api.listSkillExecutions(skillId, environmentParams) : Promise.resolve({ data: [] }), api.listOptimizationWorkItems ? api.listOptimizationWorkItems({ skillId, dataSource: "mock", runtimeId, mcpServerId, llmProviderId }) : Promise.resolve({ data: [] }), api.listOptimizationExperiments ? api.listOptimizationExperiments({ skillId }) : Promise.resolve({ data: [] }), api.listExecutionEnvironments ? api.listExecutionEnvironments() : Promise.resolve({ data: null }), api.listQualityCompatibilityMatrices ? api.listQualityCompatibilityMatrices({ skillId, skillVersion, dataSource: "mock", suiteId, suiteVersion }) : Promise.resolve({ data: [] }), api.getPackageSecurityReadiness ? api.getPackageSecurityReadiness() : Promise.resolve({ data: null })])
    .then(([runsResponse, suitesResponse, rulesResponse, contractsResponse, contractVerificationResponse, providersResponse, readinessResponse, thresholdsResponse, benchmarksResponse, suggestionsResponse, executionsResponse, workItemsResponse, experimentsResponse, executionEnvironmentsResponse, compatibilityMatricesResponse, packageSecurityReadinessResponse]) => {
      setRuns(Array.isArray(runsResponse.data) ? runsResponse.data.map(normalizeQualityEvaluationRun) : []);
      const suiteItems = suitesResponse.data ?? [];
      setSuites(suiteItems);
      if (suiteItems.length > 0) {
        const selectedId = suiteItems.some((suite) => suite.id === suiteId)
          ? suiteId : (suiteItems.find((suite) => suite.enabled)?.id || suiteItems[0]?.id || "");
        const selectedVersion = suiteItems.some((suite) => suite.id === selectedId && suite.version === suiteVersion)
          ? suiteVersion : (suiteItems.find((suite) => suite.id === selectedId && suite.enabled)?.version
            || suiteItems.find((suite) => suite.enabled)?.version || suiteItems[0]?.version || "");
        setSuiteId(selectedId);
        setSuiteVersion(selectedVersion);
      }
      setRules(normalizeQualityRules(rulesResponse));
      setThresholds(normalizeOptimizationSuggestionThresholds(thresholdsResponse));
      setProviderContracts(contractsResponse.data ?? []);
      setProviderContractVerification(contractVerificationResponse.data ?? []);
      setProviders(providersResponse.data ?? []);
      setProviderReadiness(readinessResponse.data ?? null);
      setPackageSecurityReadiness(packageSecurityReadinessResponse.data ?? null);
      setBenchmarks(normalizeQualityBenchmarks(benchmarksResponse));
      setSuggestions(suggestionsResponse.data ?? []);
      setExecutions(Array.isArray(executionsResponse.data) ? executionsResponse.data.map(normalizeSkillExecutionRecord) : []);
      setWorkItems(workItemsResponse.data ?? []);
      const experimentItems = experimentsResponse.data ?? [];
      setOptimizationExperiments(experimentItems);
      if (api.listOptimizationExperimentObservations) {
        Promise.all(experimentItems.map(async (experiment) => [experiment.experimentId,
          (await api.listOptimizationExperimentObservations(experiment.experimentId)).data ?? []]))
          .then((items) => setOptimizationObservations(Object.fromEntries(items)))
          .catch((loadError) => setError(loadError.message || "加载后发布观察失败"));
      }
      if (api.listOptimizationExperimentAssessments) {
        Promise.all(experimentItems.map(async (experiment) => [experiment.experimentId,
          (await api.listOptimizationExperimentAssessments(experiment.experimentId)).data ?? []]))
          .then((items) => setOptimizationAssessments(Object.fromEntries(items)))
          .catch((loadError) => setError(loadError.message || "加载发布后评估失败"));
      }
      setExecutionEnvironments(Array.isArray(executionEnvironmentsResponse.data) ? executionEnvironmentsResponse.data : []);
      setCompatibilityMatrices(compatibilityMatricesResponse.data ?? []);
      setWorkItemSuggestionId((current) => current || suggestionsResponse.data?.[0]?.id || "");
    });
  useEffect(() => {
    if (role !== "admin") return;
    setError("");
    loadRuns().catch((loadError) => setError(loadError.message || "质量评测记录加载失败"));
  }, [api, role, skillId, skillVersion, runtimeId, mcpServerId, llmProviderId, suiteId, suiteVersion]);
  useEffect(() => {
    if (!activeCompatibilityMatrix || !api.getQualityCompatibilityMatrix
      || !["QUEUED", "RUNNING"].includes(activeCompatibilityMatrix.status)) return undefined;
    const timer = window.setTimeout(() => {
      api.getQualityCompatibilityMatrix(activeCompatibilityMatrix.matrixRunId)
        .then((response) => {
          setActiveCompatibilityMatrix(response.data);
          setCompatibilityMatrices((items) => [response.data, ...items.filter((item) => item.matrixRunId !== response.data.matrixRunId)]);
          if (api.getQualityCompatibilityMatrixCases) return api.getQualityCompatibilityMatrixCases(response.data.matrixRunId);
          return null;
        })
        .then((response) => { if (response?.data) setCompatibilityMatrixCases(response.data); })
        .catch((loadError) => setError(loadError.message || "兼容性矩阵状态查询失败"));
    }, 250);
    return () => window.clearTimeout(timer);
  }, [api, activeCompatibilityMatrix]);
  useEffect(() => {
    if (!activeRun) return undefined;
    if (activeRun.status !== "COMPLETED") {
      setSnapshot(normalizeQualitySnapshot(null));
    } else {
      api.getQualitySnapshot(activeRun.id)
        .then((response) => setSnapshot(normalizeQualitySnapshot(response)))
        .catch((loadError) => setError(loadError.message || "质量快照加载失败"));
    }
    if (api.getQualityEvaluationResults && ["COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED"].includes(activeRun.status)) {
      api.getQualityEvaluationResults(activeRun.id)
        .then((response) => setCaseResults(Array.isArray(response.data) ? response.data : []))
        .catch((loadError) => setError(loadError.message || "评测用例结果加载失败"));
    } else if (!activeRun || !["COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED"].includes(activeRun.status)) {
      setCaseResults([]);
    }
    return undefined;
  }, [api, activeRun]);
  useEffect(() => {
    if (!activeRun || !api.getQualityEvaluation || !["QUEUED", "RUNNING"].includes(activeRun.status)) return undefined;
    const timer = window.setTimeout(() => {
      api.getQualityEvaluation(activeRun.id)
        .then((response) => { setActiveRun(response.data); setRuns((items) => [response.data, ...items.filter((item) => item.id !== response.data.id)]); })
        .catch((loadError) => setError(loadError.message || "评测状态查询失败"));
    }, 250);
    return () => window.clearTimeout(timer);
  }, [api, activeRun]);

  const submit = async (event) => {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const response = await api.submitQualityEvaluation({ skillId: skillId.trim(), skillVersion: skillVersion.trim(), suiteId, suiteVersion, scenario, timeoutMs: 1000, runtimeId: runtimeId.trim(), mcpServerId: mcpServerId.trim(), llmProviderId: llmProviderId.trim() });
      setSnapshot(normalizeQualitySnapshot(null));
      setCaseResults([]);
      setActiveRun(response.data);
      setRuns((items) => [response.data, ...items.filter((item) => item.id !== response.data.id)]);
    } catch (submitError) {
      setError(submitError.message || "提交评测失败");
    } finally {
      setLoading(false);
    }
  };

  const createSuite = async (event) => {
    event.preventDefault();
    try {
      const nextVersion = newSuiteVersion.trim() || `${newSuiteId.trim()}-v1`;
      const response = await api.createQualitySuite({ id: newSuiteId.trim(), name: newSuiteName.trim(), version: nextVersion, enabled: true, cases: newSuiteCases });
      setSuites((items) => [...items, response.data]);
      setSuiteId(response.data.id);
      setSuiteVersion(response.data.version);
      setNewSuiteId("");
      setNewSuiteName("");
      setNewSuiteVersion("");
      setNewSuiteCases([{ id: "case-1", name: "默认成功路径" }]);
      setShowSuiteForm(false);
    } catch (createError) {
      setError(createError.message || "创建评测套件失败");
    }
  };

  const saveThresholds = async (event) => {
    event.preventDefault();
    if (!api.updateOptimizationSuggestionThresholds) return;
    setThresholdSaving(true);
    setError("");
    try {
      const response = await api.updateOptimizationSuggestionThresholds(thresholds);
      setThresholds(normalizeOptimizationSuggestionThresholds(response));
    } catch (saveError) {
      setError(saveError.message || "保存优化阈值失败");
    } finally {
      setThresholdSaving(false);
    }
  };

  const probeQualityProviders = async () => {
    if (!api.probeQualityProviders) return;
    setProviderProbeLoading(true);
    setError("");
    try {
      const response = await api.probeQualityProviders();
      setProviderProbes(Array.isArray(response.data) ? response.data : []);
    } catch (probeError) {
      setError(probeError.message || "Provider 连接探测失败");
    } finally {
      setProviderProbeLoading(false);
    }
  };

  const updateSuggestionDisposition = async (suggestion, status) => {
    if (!api.updateSkillQualitySuggestionDisposition || !suggestion?.id) return;
    setSuggestionLoadingId(suggestion.id);
    setError("");
    try {
      const response = await api.updateSkillQualitySuggestionDisposition(skillId.trim(), suggestion.id, {
        version: skillVersion.trim(), window: "24h", dataSource: "mock", runtimeId: runtimeId.trim(),
        mcpServerId: mcpServerId.trim(), llmProviderId: llmProviderId.trim(), status, evidenceType: "NONE",
      });
      const updated = response.data;
      setSuggestions((items) => items.map((item) => item.id === suggestion.id ? { ...item, ...updated } : item));
    } catch (updateError) {
      setError(updateError.message || "优化建议处置失败");
    } finally {
      setSuggestionLoadingId("");
    }
  };

  const createOptimizationWorkItem = async (event) => {
    event.preventDefault();
    if (!api.createOptimizationWorkItem) return;
    const suggestion = suggestions.find((item) => item.id === workItemSuggestionId) || suggestions[0];
    if (!suggestion) return;
    setWorkItemLoadingId("new");
    setError("");
    try {
      const response = await api.createOptimizationWorkItem({
        skillId: skillId.trim(), sourceVersion: skillVersion.trim(), suggestionId: suggestion.id,
        hypothesis: workItemHypothesis.trim(), ownerId: "admin", dataSource: "mock",
        runtimeId: runtimeId.trim(), mcpServerId: mcpServerId.trim(), llmProviderId: llmProviderId.trim(),
        suiteId, suiteVersion,
      });
      setWorkItems((items) => [response.data, ...items.filter((item) => item.workItemId !== response.data.workItemId)]);
      setWorkItemHypothesis("");
    } catch (createError) {
      setError(createError.message || "创建优化工作项失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const transitionOptimizationWorkItem = async (workItem, status, input = {}) => {
    if (!api.updateOptimizationWorkItemStatus || !workItem?.workItemId) return;
    setWorkItemLoadingId(workItem.workItemId);
    setError("");
    try {
      const response = await api.updateOptimizationWorkItemStatus(workItem.workItemId, { status, ...input });
      setWorkItems((items) => items.map((item) => item.workItemId === workItem.workItemId ? response.data : item));
    } catch (transitionError) {
      setError(transitionError.message || "更新优化工作项状态失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const bindOptimizationWorkItemEvidence = async (workItem) => {
    if (!api.bindOptimizationWorkItemEvidence || !workItem?.workItemId || !workItemEvidenceId.trim()) return;
    setWorkItemLoadingId(workItem.workItemId);
    setError("");
    try {
      const response = await api.bindOptimizationWorkItemEvidence(workItem.workItemId, {
        evidenceType: workItemEvidenceType, evidenceId: workItemEvidenceId.trim(), outcome: workItemEvidenceOutcome.trim(),
      });
      setWorkItems((items) => items.map((item) => item.workItemId === workItem.workItemId ? response.data : item));
      setWorkItemEvidenceId("");
      setWorkItemEvidenceOutcome("");
    } catch (evidenceError) {
      setError(evidenceError.message || "绑定优化证据失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const updateOptimizationExperiment = (experiment) => {
    setOptimizationExperiments((items) => [experiment, ...items.filter((item) => item.experimentId !== experiment.experimentId)]);
  };

  const startOptimizationExperiment = async (workItem) => {
    if (!api.createOptimizationExperiment || !workItem?.workItemId) return;
    setWorkItemLoadingId(workItem.workItemId);
    setError("");
    try {
      const response = await api.createOptimizationExperiment({ workItemId: workItem.workItemId });
      updateOptimizationExperiment(response.data);
    } catch (experimentError) {
      setError(experimentError.message || "启动优化实验失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const reconcileOptimizationExperiment = async (experiment) => {
    if (!api.reconcileOptimizationExperiment || !experiment?.experimentId) return;
    setWorkItemLoadingId(experiment.workItemId);
    setError("");
    try {
      const response = await api.reconcileOptimizationExperiment(experiment.experimentId);
      updateOptimizationExperiment(response.data);
    } catch (experimentError) {
      setError(experimentError.message || "刷新优化实验失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const cancelOptimizationExperiment = async (experiment) => {
    if (!api.cancelOptimizationExperiment || !experiment?.experimentId) return;
    setWorkItemLoadingId(experiment.workItemId);
    setError("");
    try {
      const response = await api.cancelOptimizationExperiment(experiment.experimentId);
      updateOptimizationExperiment(response.data);
    } catch (experimentError) {
      setError(experimentError.message || "取消优化实验失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const benchmarkOptimizationExperiment = async (experiment) => {
    if (!api.benchmarkOptimizationExperiment || !experiment?.experimentId) return;
    setWorkItemLoadingId(experiment.workItemId);
    setError("");
    try {
      const response = await api.benchmarkOptimizationExperiment(experiment.experimentId, { window: benchmarkWindow });
      updateOptimizationExperiment(response.data);
    } catch (experimentError) {
      setError(experimentError.message || "生成实验 Benchmark 失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const decideOptimizationExperiment = async (experiment) => {
    if (!api.decideOptimizationExperiment || !experiment?.experimentId) return;
    setWorkItemLoadingId(experiment.workItemId);
    try {
      const response = await api.decideOptimizationExperiment(experiment.experimentId);
      updateOptimizationExperiment(response.data);
    } catch (decisionError) {
      setError(decisionError.message || "生成优化实验决策失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const captureOptimizationObservation = async (experiment) => {
    if (!api.captureOptimizationExperimentObservation || !experiment?.experimentId) return;
    setWorkItemLoadingId(experiment.workItemId);
    setError("");
    try {
      const response = await api.captureOptimizationExperimentObservation(experiment.experimentId, { window: benchmarkWindow });
      setOptimizationObservations((items) => ({
        ...items,
        [experiment.experimentId]: [response.data, ...(items[experiment.experimentId] || [])],
      }));
    } catch (observationError) {
      setError(observationError.message || "采集后发布观察失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const assessOptimizationExperiment = async (experiment, observation, action) => {
    if (!api.createOptimizationExperimentAssessment || !experiment?.experimentId || !observation?.observationId) return;
    setWorkItemLoadingId(experiment.workItemId);
    setError("");
    try {
      const response = await api.createOptimizationExperimentAssessment(experiment.experimentId, {
        observationId: observation.observationId, action, note: "",
      });
      setOptimizationAssessments((items) => ({
        ...items,
        [experiment.experimentId]: [response.data, ...(items[experiment.experimentId] || [])],
      }));
      if (action === "CREATE_FOLLOW_UP" && api.listOptimizationWorkItems) {
        const workItemsResponse = await api.listOptimizationWorkItems({
          skillId, dataSource: "mock", runtimeId, mcpServerId, llmProviderId,
        });
        setWorkItems(workItemsResponse.data ?? []);
      }
    } catch (assessmentError) {
      setError(assessmentError.message || "创建发布后评估失败");
    } finally {
      setWorkItemLoadingId("");
    }
  };

  const runBenchmark = async (event) => {
    event.preventDefault();
    if (!api.createQualityBenchmark) return;
    setBenchmarkLoading(true);
    setError("");
    try {
      const response = await api.createQualityBenchmark({
        skillId: skillId.trim(), baselineVersion: benchmarkBaseline.trim(),
        candidateVersion: skillVersion.trim(), window: benchmarkWindow, dataSource: "mock",
        runtimeId: runtimeId.trim(), mcpServerId: mcpServerId.trim(), llmProviderId: llmProviderId.trim(),
        suiteId, suiteVersion,
      });
      setBenchmarks((items) => [normalizeQualityBenchmarks({ data: [response.data] })[0], ...items]);
    } catch (runError) {
      setError(runError.message || "Benchmark 验证失败");
    } finally {
      setBenchmarkLoading(false);
    }
  };

  const executeSkill = async (event) => {
    event.preventDefault();
    if (!api.executeSkill) return;
    setExecutionLoading(true);
    setError("");
    try {
      const response = await api.executeSkill({
        skillId: skillId.trim(), skillVersion: skillVersion.trim(), scenario: executionScenario,
        timeoutMs: Number(executionTimeoutMs), runtimeId: runtimeId.trim(),
        mcpServerId: mcpServerId.trim(), llmProviderId: llmProviderId.trim(),
      });
      const normalizedExecution = normalizeSkillExecutionRecord(response.data);
      setExecutions((items) => [normalizedExecution, ...items.filter((item) => item.executionId !== normalizedExecution.executionId)]);
    } catch (executeError) {
      setError(executeError.message || "执行 Skill 失败");
    } finally {
      setExecutionLoading(false);
    }
  };

  const cancelActiveRun = async () => {
    if (!activeRun?.id || !api.cancelQualityEvaluation) return;
    setCancelLoading(true);
    setError("");
    try {
      const response = await api.cancelQualityEvaluation(activeRun.id);
      setSnapshot(normalizeQualitySnapshot(null));
      setCaseResults([]);
      setActiveRun(response.data);
      setRuns((items) => [response.data, ...items.filter((item) => item.id !== response.data.id)]);
    } catch (cancelError) {
      setError(cancelError.message || "取消评测失败");
    } finally {
      setCancelLoading(false);
    }
  };

  const createCompatibilityMatrix = async (event) => {
    event.preventDefault();
    if (!api.createQualityCompatibilityMatrix) return;
    if (matrixCatalogUnavailable) {
      setError("暂无可用的受管执行环境，暂不能创建兼容性矩阵");
      return;
    }
    if (![matrixRuntimeIds, matrixMcpServerIds, matrixLlmProviderIds].some((values) => values.length > 0)) {
      setError("至少选择一个执行环境后才能创建兼容性矩阵");
      return;
    }
    if (matrixOverLimit) {
      setError("兼容性矩阵最多允许 100 个环境组合");
      return;
    }
    setCompatibilityMatrixLoading(true);
    setError("");
    try {
      const response = await api.createQualityCompatibilityMatrix({
        skillId: skillId.trim(), skillVersion: skillVersion.trim(), suiteId, suiteVersion,
        runtimeIds: matrixRuntimeIds,
        mcpServerIds: matrixMcpServerIds,
        llmProviderIds: matrixLlmProviderIds,
        policy: compatibilityMatrixPolicy, minimumPassRate: compatibilityMatrixPolicy === "ALL_MUST_PASS" ? 1 : 0.8,
        releaseGateRequired: compatibilityMatrixReleaseGate, scenario, timeoutMs: 1000, dataSource: "mock",
      });
      setActiveCompatibilityMatrix(response.data);
      setCompatibilityMatrixCases([]);
      setCompatibilityMatrices((items) => [response.data, ...items.filter((item) => item.matrixRunId !== response.data.matrixRunId)]);
    } catch (matrixError) {
      setError(matrixError.message || "创建兼容性矩阵失败");
    } finally {
      setCompatibilityMatrixLoading(false);
    }
  };

  const selectCompatibilityMatrix = async (matrix) => {
    setActiveCompatibilityMatrix(matrix);
    if (!api.getQualityCompatibilityMatrixCases) return;
    try {
      const response = await api.getQualityCompatibilityMatrixCases(matrix.matrixRunId);
      setCompatibilityMatrixCases(response.data ?? []);
    } catch (loadError) {
      setError(loadError.message || "兼容性矩阵用例加载失败");
    }
  };

  const cancelCompatibilityMatrix = async () => {
    if (!activeCompatibilityMatrix?.matrixRunId || !api.cancelQualityCompatibilityMatrix) return;
    setCompatibilityMatrixLoading(true);
    setError("");
    try {
      const response = await api.cancelQualityCompatibilityMatrix(activeCompatibilityMatrix.matrixRunId);
      setActiveCompatibilityMatrix(response.data);
      setCompatibilityMatrices((items) => [response.data, ...items.filter((item) => item.matrixRunId !== response.data.matrixRunId)]);
      if (api.getQualityCompatibilityMatrixCases) {
        const casesResponse = await api.getQualityCompatibilityMatrixCases(response.data.matrixRunId);
        setCompatibilityMatrixCases(casesResponse.data ?? []);
      }
    } catch (cancelError) {
      setError(cancelError.message || "取消兼容性矩阵失败");
    } finally {
      setCompatibilityMatrixLoading(false);
    }
  };

  const experimentFor = (workItem) => optimizationExperiments.find((item) => item.workItemId === workItem?.workItemId);

  if (role !== "admin") return <main className="content api-state error-state">当前角色无权查看质量管理</main>;
  return <main className="content quality-content">
    <div className="page-title-row"><div><h1>质量管理中心</h1><p>使用受控 Mock Runner 对已发布 Skill 做可重复的基础评测，结果不会混入生产运营数据。</p></div></div>
    {error && <div className="api-state error-state" role="alert">{error}</div>}
    <ReleaseControlPanel api={api} role={role} skillId={skillId} version={skillVersion} />
    <section className="panel quality-security-readiness" data-testid="package-security-readiness"><div className="section-heading"><div><h2>Skill 包安全扫描</h2><p>显示上传安全门的外部扫描 readiness；状态不等同于扫描结果通过。</p></div><span className="quality-source-badge">安全门</span></div>{packageSecurityReadiness ? <div className="quality-provider-readiness"><strong>状态：{packageSecurityReadiness.status}</strong><span>模式：{packageSecurityReadiness.mode}</span><span>扫描器：{packageSecurityReadiness.scannerId || "未配置"}{packageSecurityReadiness.scannerVersion ? ` · ${packageSecurityReadiness.scannerVersion}` : ""}</span><small>原因：{packageSecurityReadiness.reasonCode || "UNKNOWN"}</small><small>已覆盖：{(packageSecurityReadiness.capabilities ?? []).join("、") || "暂无"}</small>{packageSecurityReadiness.missingCapabilities?.length ? <small>缺失能力：{packageSecurityReadiness.missingCapabilities.join("、")}</small> : null}</div> : <div className="empty-state">当前环境未提供安全扫描 readiness 接口。</div>}</section>
    {suggestions.some((suggestion) => suggestion.dispositionEvidenceStatus === "EXPIRED") && <div className="api-state warning-state" role="status">部分优化建议关联的质量证据已过期或被清理，请重新评测后再处置。</div>}
    <section className="panel quality-suggestions-panel" data-testid="quality-suggestions"><div className="section-heading"><div><h2>当前 Skill 优化建议</h2><p>建议来自质量快照、运行聚合和 Benchmark，仅供管理员复核，不会自动修改或发布 Skill。</p></div><span className="quality-source-badge">只读证据</span></div>{suggestions.length ? <div className="quality-suggestion-list">{suggestions.map((suggestion) => <article className="quality-suggestion-row" key={suggestion.id}><div><strong>{suggestion.title}</strong><small>{suggestion.severity || "INFO"} · {suggestion.dispositionStatus || "OPEN"}</small></div><p>{suggestion.recommendedAction || "暂无推荐动作"}</p><small>证据：{(suggestion.evidence || []).join("、") || "暂无"}</small></article>)}</div> : <div className="empty-state">当前 Skill 暂无优化建议。</div>}</section>
    {suggestions.length > 0 && <section className="panel quality-suggestion-actions-panel" data-testid="quality-suggestion-actions"><div className="section-heading"><div><h2>建议处置</h2><p>处置结果会写入审计链，仍需人工决定下一步评测或发布动作。</p></div></div><div className="quality-suggestion-action-list">{suggestions.map((suggestion) => <div className="quality-suggestion-action-row" key={suggestion.id}><span>{suggestion.title}</span><div>{(suggestionActions[suggestion.dispositionStatus || "OPEN"] || suggestionActions.OPEN).map(([status, label]) => <button type="button" className="text-button" key={status} disabled={suggestionLoadingId === suggestion.id} onClick={() => updateSuggestionDisposition(suggestion, status)}>{suggestionLoadingId === suggestion.id ? "保存中…" : label}</button>)}</div></div>)}</div></section>}
    <section className="panel optimization-work-items-panel" data-testid="optimization-work-items"><div className="section-heading"><div><h2>优化工作项</h2><p>把质量建议转化为可追踪的假设、候选版本和证据，不直接修改或发布 Skill。</p></div><span className="quality-source-badge">生命周期闭环</span></div>{suggestions.length > 0 && <form className="optimization-work-item-form" data-testid="optimization-work-item-form" onSubmit={createOptimizationWorkItem}><label><span>来源建议</span><select value={workItemSuggestionId || suggestions[0].id} onChange={(event) => setWorkItemSuggestionId(event.target.value)}>{suggestions.map((suggestion) => <option value={suggestion.id} key={suggestion.id}>{suggestion.title}</option>)}</select></label><label><span>优化假设</span><input value={workItemHypothesis} onChange={(event) => setWorkItemHypothesis(event.target.value)} placeholder="例如：降低依赖超时导致的 P95" required /></label><button className="primary-action" type="submit" disabled={workItemLoadingId === "new" || !suiteSelectionAvailable}>{workItemLoadingId === "new" ? "创建中…" : "创建优化工作项"}</button></form>}{workItems.length ? <div className="optimization-work-item-list">{workItems.map((workItem) => <article className="optimization-work-item-row" key={workItem.workItemId}><div><strong>{workItem.suggestionTitle || workItem.suggestionId}</strong><small>{workItem.workItemId} · 来源版本 {workItem.sourceVersion} · Owner {workItem.ownerId || "未分配"} · {workItem.suiteId && workItem.suiteVersion ? `套件 ${workItem.suiteId} · 版本 ${workItem.suiteVersion}` : "未锁定套件版本"}</small><p>{workItem.hypothesis || "未填写优化假设"}</p>{workItem.suggestionEvidence?.length ? <small>来源证据：{workItem.suggestionEvidence.join("、")}</small> : null}</div><div className="optimization-work-item-meta"><span className={`quality-status quality-status-${String(workItem.status || "OPEN").toLowerCase()}`}>{workItem.status || "OPEN"}</span>{workItem.status === "OPEN" && <button type="button" className="text-button" data-testid="optimization-work-item-status" disabled={workItemLoadingId === workItem.workItemId} onClick={() => transitionOptimizationWorkItem(workItem, "PLANNED")}>规划</button>}{workItem.status === "PLANNED" && <button type="button" className="text-button" data-testid="optimization-work-item-status" disabled={workItemLoadingId === workItem.workItemId} onClick={() => transitionOptimizationWorkItem(workItem, "IN_PROGRESS")}>开始实施</button>}{workItem.status === "IN_PROGRESS" && <><label><span>候选版本</span><input value={workItemCandidateVersion} onChange={(event) => setWorkItemCandidateVersion(event.target.value)} /></label><button type="button" className="text-button" data-testid="optimization-work-item-status" disabled={workItemLoadingId === workItem.workItemId} onClick={() => transitionOptimizationWorkItem(workItem, "READY_FOR_EVALUATION", { candidateVersion: workItemCandidateVersion.trim() })}>提交评测</button></>}{workItem.status === "READY_FOR_EVALUATION" && <div className="optimization-work-item-evidence"><select value={workItemEvidenceType} onChange={(event) => setWorkItemEvidenceType(event.target.value)}><option value="BENCHMARK">Benchmark</option><option value="EVALUATION_RUN">评测运行</option><option value="QUALITY_SNAPSHOT">质量快照</option><option value="POST_RELEASE_ASSESSMENT">发布后效果评估</option></select><input data-testid="optimization-work-item-evidence-id" placeholder="证据 ID" value={workItemEvidenceId} onChange={(event) => setWorkItemEvidenceId(event.target.value)} /><input data-testid="optimization-work-item-evidence-outcome" placeholder="效果结论" value={workItemEvidenceOutcome} onChange={(event) => setWorkItemEvidenceOutcome(event.target.value)} /><button type="button" className="text-button" data-testid="optimization-work-item-evidence" disabled={workItemLoadingId === workItem.workItemId || !workItemEvidenceId.trim()} onClick={() => bindOptimizationWorkItemEvidence(workItem)}>绑定证据</button>{workItem.evidenceType && workItem.evidenceType !== "NONE" && workItem.evidenceId && <><small>已绑定：{workItem.evidenceType} · {workItem.evidenceId}{workItem.outcome ? ` · ${workItem.outcome}` : ""}</small><button type="button" className="text-button" data-testid="optimization-work-item-complete" disabled={workItemLoadingId === workItem.workItemId || !workItem.outcome} onClick={() => transitionOptimizationWorkItem(workItem, "COMPLETED", { outcome: workItem.outcome })}>完成工作项</button></>}</div>}</div></article>)}</div> : <div className="empty-state">暂无优化工作项，请从上方建议创建。</div>}</section>
    {workItems.some((item) => item.evidenceType && item.evidenceType !== "NONE" && item.evidenceId) && <section className="panel optimization-work-item-evidence-ledger" data-testid="optimization-work-item-evidence-ledger"><div className="section-heading"><div><h2>优化证据台账</h2><p>保留证据类型、受控 ID 和结果说明，方便回溯优化是否有效。</p></div></div>{workItems.filter((item) => item.evidenceType && item.evidenceType !== "NONE" && item.evidenceId).map((workItem) => <div className="optimization-work-item-evidence-row" key={workItem.workItemId}><span>{workItem.suggestionTitle || workItem.suggestionId} · {workItem.status}</span><strong>{workItem.evidenceType} · {workItem.evidenceId}</strong><small>{workItem.outcome || "未填写结果说明"}</small></div>)}</section>}
    {workItems.some((item) => item.status === "ABANDONED" || !["COMPLETED", "ABANDONED"].includes(item.status)) && <section className="panel optimization-work-item-lifecycle-actions" data-testid="optimization-work-item-lifecycle-actions"><div className="section-heading"><div><h2>工作项处置</h2><p>放弃必须记录原因；已放弃工作项可以重新打开继续迭代。</p></div></div>{workItems.filter((item) => item.status !== "COMPLETED").map((workItem) => <div className="optimization-work-item-action-row" key={workItem.workItemId}><span>{workItem.suggestionTitle || workItem.suggestionId} · {workItem.status}</span>{workItem.status === "ABANDONED" ? <button type="button" className="text-button" data-testid="optimization-work-item-reopen" disabled={workItemLoadingId === workItem.workItemId} onClick={() => transitionOptimizationWorkItem(workItem, "OPEN")}>重新打开</button> : <><input placeholder="放弃原因" value={workItemOutcome} onChange={(event) => setWorkItemOutcome(event.target.value)} /><button type="button" className="text-button" data-testid="optimization-work-item-abandon" disabled={workItemLoadingId === workItem.workItemId || !workItemOutcome.trim()} onClick={() => transitionOptimizationWorkItem(workItem, "ABANDONED", { outcome: workItemOutcome.trim() })}>放弃</button></>}</div>)}</section>}
    <section className="panel optimization-experiments-panel" data-testid="optimization-experiments"><div className="section-heading"><div><h2>优化实验</h2><p>实验只验证候选版本并回写质量证据，不自动完成工作项或发布 Skill。</p></div></div>{workItems.filter((item) => item.status === "READY_FOR_EVALUATION").map((workItem) => { const experiment = experimentFor(workItem); return <div className="optimization-experiment-row" key={workItem.workItemId}><span>{workItem.workItemId} · {experiment ? `${experiment.status} · Run ${experiment.evaluationRunId || "未提交"}` : "尚未启动"}</span>{!experiment && api.createOptimizationExperiment && <button type="button" className="text-button" data-testid="optimization-experiment-start" disabled={workItemLoadingId === workItem.workItemId} onClick={() => startOptimizationExperiment(workItem)}>启动实验</button>}{experiment && ["QUEUED", "RUNNING"].includes(experiment.status) && api.reconcileOptimizationExperiment && <button type="button" className="text-button" data-testid="optimization-experiment-reconcile" disabled={workItemLoadingId === workItem.workItemId} onClick={() => reconcileOptimizationExperiment(experiment)}>刷新实验</button>}{experiment && ["QUEUED", "RUNNING"].includes(experiment.status) && api.cancelOptimizationExperiment && <button type="button" className="text-button" data-testid="optimization-experiment-cancel" disabled={workItemLoadingId === workItem.workItemId} onClick={() => cancelOptimizationExperiment(experiment)}>取消实验</button>}{experiment?.status === "COMPLETED" && !experiment.benchmarkId && api.benchmarkOptimizationExperiment && <button type="button" className="text-button" data-testid="optimization-experiment-benchmark" disabled={workItemLoadingId === workItem.workItemId} onClick={() => benchmarkOptimizationExperiment(experiment)}>生成 Benchmark</button>}{experiment?.status === "COMPLETED" && !experiment.benchmarkId && <small>先生成 Benchmark</small>}{experiment?.status === "COMPLETED" && experiment.benchmarkId && !experiment.decision && api.decideOptimizationExperiment && <button type="button" className="text-button" data-testid="optimization-experiment-decision" disabled={workItemLoadingId === workItem.workItemId} onClick={() => decideOptimizationExperiment(experiment)}>生成决策</button>}{experiment?.decision && <small data-testid="optimization-experiment-decision-result">决策：{experiment.decision.decision} · {experiment.decision.reasonCode} · {experiment.decision.recommendedAction}</small>}{experiment && <small>{experiment.qualitySnapshotId ? `Snapshot ${experiment.qualitySnapshotId}` : ""}{experiment.benchmarkId ? ` · Benchmark ${experiment.benchmarkId}` : ""}</small>}</div>; })}</section>
    {api.listOptimizationExperimentObservations && <section className="panel optimization-observations-panel" data-testid="optimization-observations"><div className="section-heading"><div><h2>发布后运行观察</h2><p>仅对已发布且决策为 Promote 的候选版本采集脱敏运行聚合；观察记录追加保存，不自动回滚或改变发布状态。</p></div><span className="quality-source-badge">运行证据</span></div>{optimizationExperiments.filter((experiment) => experiment.decision?.decision === "PROMOTE_CANDIDATE").length ? optimizationExperiments.filter((experiment) => experiment.decision?.decision === "PROMOTE_CANDIDATE").map((experiment) => <article className="optimization-observation-row" key={experiment.experimentId}><div><strong>{experiment.skillId} · {experiment.candidateVersion}</strong><small>{experiment.experimentId} · {experiment.dataSource} · {experiment.runtimeId || "未指定 Runtime"}</small></div><div className="optimization-observation-actions"><button type="button" className="text-button" data-testid="optimization-observation-capture" disabled={workItemLoadingId === experiment.workItemId || !api.captureOptimizationExperimentObservation} onClick={() => captureOptimizationObservation(experiment)}>采集 {benchmarkWindow} 观察</button>{(optimizationObservations[experiment.experimentId] || []).slice(0, 3).map((observation) => <span className={`quality-status quality-status-${String(observation.observationStatus || "").toLowerCase()}`} key={observation.observationId}>{observation.observationStatus} · {observation.totalCalls ?? 0} 次 · 成功率 {observation.successRate ?? 0}% · P95 {observation.p95Ms ?? 0}ms</span>)}</div></article>) : <div className="empty-state">暂无可观察的 Promote 候选版本；请先完成评测、Benchmark、决策并发布。</div>}</section>}
    {api.listOptimizationExperimentAssessments && <section className="panel optimization-assessments-panel" data-testid="optimization-assessments"><div className="section-heading"><div><h2>发布后效果评估</h2><p>基于同窗口源版本基线与候选版本观察生成结论；人工动作只写入证据和审计，不自动回滚。</p></div><span className="quality-source-badge">人工处置</span></div>{optimizationExperiments.filter((experiment) => experiment.decision?.decision === "PROMOTE_CANDIDATE").map((experiment) => { const latestObservation = (optimizationObservations[experiment.experimentId] || [])[0]; const latestAssessment = (optimizationAssessments[experiment.experimentId] || [])[0]; return <article className="optimization-assessment-row" key={experiment.experimentId}><div><strong>{experiment.skillId} · {experiment.candidateVersion}</strong><small>{latestObservation ? `观察 ${latestObservation.observationId}` : "请先采集观察"}</small>{latestAssessment && <span className="quality-status quality-status-captured">{latestAssessment.conclusion} · 推荐 {latestAssessment.recommendedAction} · 处置 {latestAssessment.action}</span>}</div>{latestObservation && !latestAssessment && <div className="optimization-assessment-actions"><button type="button" className="text-button" data-testid="optimization-assessment-keep" disabled={workItemLoadingId === experiment.workItemId} onClick={() => assessOptimizationExperiment(experiment, latestObservation, "KEEP")}>保留版本</button><button type="button" className="text-button" data-testid="optimization-assessment-follow-up" disabled={workItemLoadingId === experiment.workItemId} onClick={() => assessOptimizationExperiment(experiment, latestObservation, "CREATE_FOLLOW_UP")}>创建后续优化</button><button type="button" className="text-button" data-testid="optimization-assessment-rollback" disabled={workItemLoadingId === experiment.workItemId} onClick={() => assessOptimizationExperiment(experiment, latestObservation, "ROLLBACK_REVIEW")}>发起回滚复核</button></div>}</article>; })}</section>}
    <div className="quality-environment-hint">执行环境标识（可选）：{runtimeId || "未指定 Runtime"}{mcpServerId ? ` · MCP ${mcpServerId}` : ""}{llmProviderId ? ` · LLM ${llmProviderId}` : ""}</div>
    <div className="quality-environment-form" data-testid="execution-environment-catalog">{executionEnvironments.length > 0 ? <>
      <label>Runtime ID<select aria-label="Runtime ID" value={runtimeId} onChange={(event) => setRuntimeId(event.target.value)}><option value="">未指定</option>{executionEnvironments.filter((environment) => environment.kind === "AGENT_RUNTIME").map((environment) => <option key={environment.environmentId} value={environment.environmentId} disabled={environment.status !== "ACTIVE"}>{environment.environmentId} · {environment.version} · {environment.status}</option>)}</select></label>
      <label>MCP Server ID<select aria-label="MCP Server ID" value={mcpServerId} onChange={(event) => setMcpServerId(event.target.value)}><option value="">未指定</option>{executionEnvironments.filter((environment) => environment.kind === "MCP_SERVER").map((environment) => <option key={environment.environmentId} value={environment.environmentId} disabled={environment.status !== "ACTIVE"}>{environment.environmentId} · {environment.version} · {environment.status}</option>)}</select></label>
      <label>LLM Provider ID<select aria-label="LLM Provider ID" value={llmProviderId} onChange={(event) => setLlmProviderId(event.target.value)}><option value="">未指定</option>{executionEnvironments.filter((environment) => environment.kind === "LLM_PROVIDER").map((environment) => <option key={environment.environmentId} value={environment.environmentId} disabled={environment.status !== "ACTIVE"}>{environment.environmentId} · {environment.version} · {environment.status}</option>)}</select></label>
      <small>选择受管执行环境资产；降级或停用资产不可用于新评测和执行。</small>
    </> : <>
      <label>Runtime ID<input aria-label="Runtime ID" value={runtimeId} onChange={(event) => setRuntimeId(event.target.value)} placeholder="openclaw" /></label><label>MCP Server ID<input aria-label="MCP Server ID" value={mcpServerId} onChange={(event) => setMcpServerId(event.target.value)} placeholder="mcp-network" /></label><label>LLM Provider ID<input aria-label="LLM Provider ID" value={llmProviderId} onChange={(event) => setLlmProviderId(event.target.value)} placeholder="llm-gateway" /></label>
    </>}</div>
    {api.listQualityCompatibilityMatrices && <section className="panel compatibility-matrix-panel" data-testid="compatibility-matrix-panel"><div className="section-heading"><div><h2>跨执行环境兼容性矩阵</h2><p>把 Runtime、MCP Server、LLM Provider 组合成可追踪的质量证据，并可选作为版本发布门禁。</p></div><span className="quality-source-badge">质量证据</span></div>{matrixCatalogUnavailable && <div className="api-state warning-state">暂无可用的受管执行环境，请先注册并启用 Runtime、MCP Server 或 LLM Provider。</div>}<form className="quality-form" onSubmit={createCompatibilityMatrix}><label><span>Runtime（可多选）</span><select multiple aria-label="Matrix Runtime IDs" value={matrixRuntimeIds} onChange={(event) => setMatrixRuntimeIds(Array.from(event.target.selectedOptions, (option) => option.value))}>{executionEnvironments.filter((environment) => environment.kind === "AGENT_RUNTIME").map((environment) => <option key={environment.environmentId} value={environment.environmentId} disabled={environment.status !== "ACTIVE"}>{environment.environmentId} · {environment.version} · {environment.status}</option>)}</select></label><label><span>MCP Server（可多选）</span><select multiple aria-label="Matrix MCP Server IDs" value={matrixMcpServerIds} onChange={(event) => setMatrixMcpServerIds(Array.from(event.target.selectedOptions, (option) => option.value))}>{executionEnvironments.filter((environment) => environment.kind === "MCP_SERVER").map((environment) => <option key={environment.environmentId} value={environment.environmentId} disabled={environment.status !== "ACTIVE"}>{environment.environmentId} · {environment.version} · {environment.status}</option>)}</select></label><label><span>LLM Provider（可多选）</span><select multiple aria-label="Matrix LLM Provider IDs" value={matrixLlmProviderIds} onChange={(event) => setMatrixLlmProviderIds(Array.from(event.target.selectedOptions, (option) => option.value))}>{executionEnvironments.filter((environment) => environment.kind === "LLM_PROVIDER").map((environment) => <option key={environment.environmentId} value={environment.environmentId} disabled={environment.status !== "ACTIVE"}>{environment.environmentId} · {environment.version} · {environment.status}</option>)}</select></label><label><span>策略</span><select value={compatibilityMatrixPolicy} onChange={(event) => setCompatibilityMatrixPolicy(event.target.value)}><option value="ALL_MUST_PASS">全部通过</option><option value="MIN_PASS_RATE">最低通过率 80%</option></select></label><label><span>发布门禁</span><input type="checkbox" checked={compatibilityMatrixReleaseGate} onChange={(event) => setCompatibilityMatrixReleaseGate(event.target.checked)} /></label><div className={matrixOverLimit ? "api-state error-state" : "quality-environment-hint"}>预计组合：{matrixCombinationCount} / 100{matrixOverLimit ? "，已超过上限" : ""}</div><button className="primary-action" type="submit" disabled={compatibilityMatrixLoading || matrixOverLimit || matrixCatalogUnavailable}>{compatibilityMatrixLoading ? "创建中…" : "运行兼容性矩阵"}</button></form>{activeCompatibilityMatrix && <div className="quality-run-panel"><div className="section-heading"><div><strong>{activeCompatibilityMatrix.matrixRunId}</strong><small>{activeCompatibilityMatrix.totalCases} 个组合 · {activeCompatibilityMatrix.completedCases} 个已完成</small></div><div><span className={`quality-status quality-status-${String(activeCompatibilityMatrix.status || "").toLowerCase()}`}>{statusLabel(activeCompatibilityMatrix.status)}</span>{["QUEUED", "RUNNING"].includes(activeCompatibilityMatrix.status) && api.cancelQualityCompatibilityMatrix && <button type="button" className="secondary-button" onClick={cancelCompatibilityMatrix} disabled={compatibilityMatrixLoading}>{compatibilityMatrixLoading ? "取消中…" : "取消矩阵"}</button>}</div></div>{compatibilityMatrixCases.length > 0 && <div className="quality-case-results">{compatibilityMatrixCases.map((matrixCase) => <div className="quality-case-result" key={matrixCase.caseId}><span><strong>{matrixCase.runtimeId || "未指定 Runtime"}</strong><small>{matrixCase.mcpServerId ? `MCP ${matrixCase.mcpServerId}` : ""}{matrixCase.llmProviderId ? ` · LLM ${matrixCase.llmProviderId}` : ""}</small></span><b className={matrixCase.gateStatus === "PASSED" ? "quality-pass-text" : "quality-block-text"}>{matrixCase.status} · {matrixCase.score}</b></div>)}</div>}</div>}{compatibilityMatrices.length > 0 && <div className="quality-history-list">{compatibilityMatrices.slice(0, 8).map((matrix) => <button className="quality-history-row" type="button" key={matrix.matrixRunId} onClick={() => selectCompatibilityMatrix(matrix)}><span><strong>{matrix.skillId}</strong><small>{matrix.skillVersion} · {matrix.totalCases} 个组合{matrix.releaseGateRequired ? " · 发布门禁" : ""}</small></span><span>{statusLabel(matrix.status)}</span><small>{matrix.gateStatus}</small></button>)}</div>}</section>}
    <section className="panel runner-execution-panel" data-testid="runner-execution-panel"><div className="section-heading"><div><h2>受控执行 Skill</h2><p>仅对已发布版本调用 Mock Runner，不保存业务正文；执行结果会进入运行运营统计。</p></div><span className="quality-source-badge">Mock Runner</span></div><form className="runner-execution-form" onSubmit={executeSkill}><label><span>故障注入</span><select value={executionScenario} onChange={(event) => setExecutionScenario(event.target.value)}><option value="success">正常成功</option><option value="failure">失败</option><option value="timeout">超时</option><option value="cancel">取消</option></select></label><label><span>超时（ms）</span><input type="number" min="1" max="120000" value={executionTimeoutMs} onChange={(event) => setExecutionTimeoutMs(event.target.value)} /></label><button className="primary-action" type="submit" disabled={executionLoading || !api.executeSkill}>{executionLoading ? "执行中…" : "立即执行"}</button></form>{executions.length > 0 && <div className="runner-execution-history" data-testid="runner-execution-history"><strong>最近执行</strong>{executions.slice(0, 8).map((execution) => <div className="runner-execution-row" key={execution.executionId}><span><b>{execution.executionId}</b><small>{execution.skillId} · {execution.skillVersion} · {execution.dataSource || "mock"}</small></span><span className={`quality-status quality-status-${String(execution.status || "").toLowerCase()}`}>{execution.status}</span><small>{execution.durationMs ?? 0}{formatExecutionEnvironmentSnapshot(execution.runtimeEnvironment, execution.runtimeId) ? ` ms · Runtime ${formatExecutionEnvironmentSnapshot(execution.runtimeEnvironment, execution.runtimeId)}` : " ms"}</small></div>)}</div>}</section>
    <section className="panel quality-submit-panel"><div className="section-heading"><div><h2>提交 Mock 评测</h2><p>选择已启用的评测套件版本，执行结果会标记为 mock。</p></div><span className="quality-source-badge">dataSource=mock</span></div><form className="quality-form" onSubmit={submit}><label><span>Skill ID</span><input value={skillId} onChange={(event) => setSkillId(event.target.value)} required /></label><label><span>版本</span><input value={skillVersion} onChange={(event) => setSkillVersion(event.target.value)} required /></label><label><span>评测套件版本</span><select value={`${suiteId}\u0000${suiteVersion}`} onChange={(event) => { const [nextId, nextVersion] = event.target.value.split("\u0000"); setSuiteId(nextId); setSuiteVersion(nextVersion); }}>{suites.filter((suite) => suite.enabled).map((suite) => <option value={`${suite.id}\u0000${suite.version}`} key={`${suite.id}-${suite.version}`}>{suite.name} · {suite.version}</option>)}</select></label><label><span>故障注入</span><select value={scenario} onChange={(event) => setScenario(event.target.value)}><option value="success">正常成功</option><option value="failure">失败</option><option value="timeout">超时</option><option value="cancel">取消</option></select></label><button className="primary-action" type="submit" disabled={loading || !suiteVersion}>{loading ? "提交中…" : "开始评测"}</button></form></section>
    <section className="panel quality-provider-contracts" data-testid="active-providers"><div className="section-heading"><div><h2>当前 Provider 状态</h2><p>展示本环境实际注册的执行、评测与观测 Provider，作为接入前 readiness 检查。</p></div><span className="quality-source-badge">运行态</span></div>{providerReadiness && <div className="quality-provider-readiness" data-testid="provider-readiness"><strong>整体状态：{providerReadiness.status}</strong><span>{providerReadiness.healthyProviderCount ?? 0}/{providerReadiness.activeProviderCount ?? 0} 个活动 Provider 健康</span><span>{providerReadiness.contractOnlyProviderCount ?? 0} 个外部契约</span>{providerReadiness.notConfiguredProviderCount > 0 && <span>{providerReadiness.notConfiguredProviderCount} 个未配置</span>}<small>{providerReadinessReason(providerReadiness.reason, providerReadiness.reasonDescription)} · {providerReadiness.reason}</small>{providerReadiness.contractOnlyProviderIds?.length ? <small>待接入 Provider：{providerReadiness.contractOnlyProviderIds.join("、")}</small> : null}{providerReadiness.notConfiguredProviderIds?.length ? <small>配置缺失 Provider：{providerReadiness.notConfiguredProviderIds.join("、")}</small> : null}</div>}<div className="quality-provider-probe-toolbar"><span>连接探测只验证配置端点可达性，不代表外部适配器已具备 Skill 执行能力。</span>{api.probeQualityProviders && <button type="button" className="secondary-button" data-testid="provider-connectivity-probe" onClick={probeQualityProviders} disabled={providerProbeLoading}>{providerProbeLoading ? "探测中…" : "执行连接探测"}</button>}</div>{providerProbes.length > 0 && <div className="quality-provider-probe-results" data-testid="provider-probe-results">{providerProbes.map((probe) => <div className="quality-provider-probe-row" key={probe.providerId}><strong>{probe.providerId}</strong><span>{probe.status} · {probe.reason}</span><small>{probe.httpStatus ? `HTTP ${probe.httpStatus} · ` : ""}{probe.latencyMs ?? 0} ms</small></div>)}</div>}<div className="quality-provider-contract-list">{providers.map((provider) => <div className="quality-provider-contract" key={provider.id}><div><strong>{provider.id}</strong><small>{provider.kind} · {provider.version}{provider.healthReason ? ` · ${provider.healthReason}` : ""}</small></div><span className={`quality-provider-status quality-provider-status-${String(provider.status || "unknown").toLowerCase()}`}>{provider.status || "UNKNOWN"}</span><p>{(provider.capabilities ?? []).join(" · ") || "暂无能力声明"}</p></div>)}</div>{!providers.length && <div className="empty-state">暂无已注册 Provider。</div>}</section>
    <section className="panel quality-provider-contracts" data-testid="provider-contract-verification"><div className="section-heading"><div><h2>Provider 契约一致性</h2><p>只比较版本与能力声明，不发起网络调用；一致不等于已具备真实执行能力。</p></div><span className="quality-source-badge">只读校验</span></div>{providerContractVerification.length ? <div className="quality-provider-contract-list">{providerContractVerification.map((item) => <div className="quality-provider-contract" key={item.providerId}><div><strong>{item.providerId}</strong><small>{item.kind} · 目录 {item.expectedVersion} · 当前 {item.actualVersion || "未注册"}</small></div><span className={`quality-provider-status quality-provider-status-${String(item.verificationStatus || "unknown").toLowerCase()}`}>{item.verificationStatus}</span><p>{item.reason}{item.activeStatus ? ` · 执行状态 ${item.activeStatus}` : ""}</p>{item.missingCapabilities?.length ? <small>缺少能力：{item.missingCapabilities.join("、")}</small> : null}{item.unexpectedCapabilities?.length ? <small>多余能力：{item.unexpectedCapabilities.join("、")}</small> : null}</div>)}</div> : <div className="empty-state">暂无契约校验结果。</div>}</section>
    <section className="panel quality-provider-contracts" data-testid="provider-contracts"><div className="section-heading"><div><h2>Provider 适配契约</h2><p>外部 Provider 仅展示契约状态，MVP 不发起生产调用。</p></div><span className="quality-source-badge">contract-v1</span></div><div className="quality-provider-contract-list">{providerContracts.map((provider) => <div className="quality-provider-contract" key={provider.id}><div><strong>{provider.id}</strong><small>{provider.kind} · {provider.version} · {provider.configReference || "未配置密钥引用"}</small></div><span className={`quality-provider-status quality-provider-status-${String(provider.status || "unknown").toLowerCase()}`}>{provider.status || "UNKNOWN"}</span><p>{(provider.capabilities ?? []).join(" · ") || "暂无能力声明"}</p></div>)}</div></section>
    <section className="quality-management-grid"><section className="panel quality-suites-panel" data-testid="quality-suites"><div className="section-heading"><div><h2>评测套件</h2><p>逻辑套件下保留不可变版本，停用版本仍可追溯。</p></div><button className="secondary-button" onClick={() => { setShowSuiteForm((value) => !value); setNewSuiteCases([{ id: "case-1", name: "默认成功路径" }]); }}>{showSuiteForm ? "取消" : "创建套件"}</button></div>{showSuiteForm && <form className="quality-suite-form" onSubmit={createSuite}><input placeholder="套件 ID" aria-label="新套件 ID" value={newSuiteId} onChange={(event) => setNewSuiteId(event.target.value)} required /><input placeholder="套件名称" aria-label="新套件名称" value={newSuiteName} onChange={(event) => setNewSuiteName(event.target.value)} required /><input placeholder="套件版本" aria-label="新套件版本" value={newSuiteVersion} onChange={(event) => setNewSuiteVersion(event.target.value)} /><button className="primary-action" type="submit">保存版本</button></form>}<div className="quality-suite-list">{suites.map((suite) => <div className={`quality-suite-row ${suite.id === suiteId && suite.version === suiteVersion ? "active" : ""}`} key={`${suite.id}-${suite.version}`}><button type="button" className="quality-suite-select" onClick={() => { setSuiteId(suite.id); setSuiteVersion(suite.version); }}><span><strong>{suite.name}</strong><small>{suite.id} · {suite.version} · {suite.cases?.length ?? 0} 个用例</small></span><em>{suite.enabled ? "启用" : "停用"}</em></button><button type="button" className="text-button" data-testid="suite-copy" onClick={() => { setNewSuiteId(suite.id); setNewSuiteName(suite.name); setNewSuiteVersion(`${suite.id}-v${(suites.filter((item) => item.id === suite.id).length || 0) + 1}`); setNewSuiteCases((suite.cases || []).map((item) => ({ id: item.id, name: item.name }))); setShowSuiteForm(true); }}>复制为新版本</button></div>)}</div></section><section className="panel quality-rules-panel"><div className="section-heading"><div><h2>质量门禁</h2><p>当前规则版本：{rules.version}</p></div><span className="quality-source-badge">规则生效</span></div><div className="quality-rule-list"><div><span>最低质量分</span><strong>{rules.minScore}</strong></div><div><span>最低通过率</span><strong>{Math.round(rules.minPassRate * 100)}%</strong></div><div><span>最低静态分</span><strong>{rules.minStaticScore}</strong></div></div></section></section>
    <section className="panel quality-thresholds-panel"><div className="section-heading"><div><h2>优化建议阈值</h2><p>仅影响建议计算，不会修改 Skill、评测结果或运行数据。</p></div><span className="quality-source-badge">管理员配置</span></div><form className="quality-threshold-form" onSubmit={saveThresholds}><label><span>最低成功率（%）</span><input type="number" min="0" max="100" step="0.1" value={thresholds.minSuccessRatePercent} onChange={(event) => setThresholds({ ...thresholds, minSuccessRatePercent: Number(event.target.value) })} /></label><label><span>最大 P95（ms）</span><input type="number" min="1" max="600000" value={thresholds.maxP95Ms} onChange={(event) => setThresholds({ ...thresholds, maxP95Ms: Number(event.target.value) })} /></label><label><span>最小运行样本</span><input type="number" min="1" max="1000000" value={thresholds.minRuntimeSamples} onChange={(event) => setThresholds({ ...thresholds, minRuntimeSamples: Number(event.target.value) })} /></label><button className="primary-action" type="submit" disabled={thresholdSaving}>{thresholdSaving ? "保存中…" : "保存阈值"}</button></form></section>
    <section className="panel quality-benchmark-panel"><div className="section-heading"><div><h2>Benchmark 效果验证</h2><p>复用同口径质量快照与脱敏运行聚合，验证候选版本相对基线的效果。</p></div><span className="quality-source-badge">dataSource=mock</span></div><p className="quality-benchmark-context">评测套件：{suiteId} · {suiteVersion || "未选择版本"} · 当前执行环境：{runtimeId || "未指定 Runtime"}{mcpServerId ? ` · MCP ${mcpServerId}` : ""}{llmProviderId ? ` · LLM ${llmProviderId}` : ""}</p><form className="quality-benchmark-form" onSubmit={runBenchmark}><label><span>Skill ID</span><input value={skillId} onChange={(event) => setSkillId(event.target.value)} required /></label><label><span>基线版本</span><input value={benchmarkBaseline} onChange={(event) => setBenchmarkBaseline(event.target.value)} required /></label><label><span>候选版本</span><input value={skillVersion} onChange={(event) => setSkillVersion(event.target.value)} required /></label><label><span>窗口</span><select value={benchmarkWindow} onChange={(event) => setBenchmarkWindow(event.target.value)}><option value="24h">24h</option><option value="7d">7d</option></select></label><button className="primary-action" type="submit" disabled={benchmarkLoading || !suiteVersion}>{benchmarkLoading ? "验证中…" : "运行 Benchmark"}</button></form>{benchmarks.length ? <div className="quality-benchmark-list">{benchmarks.slice(0, 8).map((benchmark) => { const evidence = benchmark.comparison?.candidate || benchmark.comparison?.baseline || benchmark; const runtimeLabel = formatExecutionEnvironmentSnapshot(evidence.runtimeEnvironment, benchmark.runtimeId || evidence.runtimeId); const mcpServerLabel = formatExecutionEnvironmentSnapshot(evidence.mcpServerEnvironment, benchmark.mcpServerId || evidence.mcpServerId); const llmProviderLabel = formatExecutionEnvironmentSnapshot(evidence.llmProviderEnvironment, benchmark.llmProviderId || evidence.llmProviderId); return <div className="quality-benchmark-row" key={benchmark.benchmarkId || benchmark.id}><span><strong>{benchmark.baselineVersion} → {benchmark.candidateVersion}</strong><small>{benchmark.window} · {benchmark.dataSource} · {benchmark.createdAt || ""}{benchmark.suiteVersion ? ` · 套件 ${benchmark.suiteVersion}` : ""}{runtimeLabel ? ` · Runtime ${runtimeLabel}` : ""}{mcpServerLabel ? ` · MCP ${mcpServerLabel}` : ""}{llmProviderLabel ? ` · LLM ${llmProviderLabel}` : ""}</small></span><b className={`benchmark-conclusion benchmark-${String(benchmark.conclusion || "NOT_COMPARABLE").toLowerCase()}`}>{benchmark.conclusion}</b></div>; })}</div> : <div className="empty-state">暂无 Benchmark 记录，请先完成两个版本的受控评测。</div>}</section>
    {activeRun && <section className="panel quality-run-panel"><div className="section-heading"><div><h2>最近任务</h2><p>{activeRun.id} · {activeRun.suiteVersion}</p></div><div className="quality-run-status-actions"><strong className={`quality-status quality-status-${String(activeRun.status).toLowerCase()}`}>{statusLabel(activeRun.status)}</strong>{["QUEUED", "RUNNING"].includes(activeRun.status) && <button type="button" className="secondary-button" onClick={cancelActiveRun} disabled={cancelLoading}>{cancelLoading ? "取消中…" : "取消评测"}</button>}</div></div><div className="quality-kpis"><div><span>质量分</span><strong>{activeRun.score ?? 0}</strong></div><div><span>通过用例</span><strong>{activeRun.passedCases ?? 0} / {activeRun.totalCases ?? 0}</strong></div><div><span>静态质量分</span><strong>{snapshot.staticScore}</strong></div><div><span>质量门禁</span><strong className={snapshot.gateStatus === "PASSED" ? "quality-pass-text" : "quality-block-text"}>{snapshot.gateStatus === "PASSED" ? "通过" : "阻断"}</strong></div></div>{snapshot.gateReasons.length > 0 && <div className="quality-gate-reasons">{snapshot.gateReasons.map((reason) => <span key={reason}>{reason}</span>)}</div>}{caseResults.length > 0 && <div className="quality-case-results" aria-label="评测用例结果">{caseResults.map((result) => <div className="quality-case-result" key={`${result.runId}-${result.caseId}`}><span><strong>{result.caseName || result.caseId}</strong><small>{result.caseId} · {result.durationMs ?? 0} ms</small></span><b className={result.passed ? "quality-pass-text" : "quality-block-text"}>{result.passed ? `通过 · ${result.score}` : `失败 · ${result.reason || result.errorCode || "未知原因"}`}</b></div>)}</div>}</section>}
    <section className="panel quality-history-panel"><div className="section-heading"><div><h2>评测历史</h2><p>按 Skill 查看可追溯的评测任务和质量分。</p></div><button className="secondary-button" onClick={() => loadRuns().catch((loadError) => setError(loadError.message || "评测记录加载失败"))}>刷新</button></div>{runs.length ? <div className="quality-history-list">{runs.map((run) => <button className="quality-history-row" key={run.id} onClick={() => setActiveRun(run)}><span><strong>{run.skillId}</strong><small>{run.skillVersion} · {run.suiteVersion}{formatExecutionEnvironmentSnapshot(run.runtimeEnvironment, run.runtimeId) ? ` · Runtime ${formatExecutionEnvironmentSnapshot(run.runtimeEnvironment, run.runtimeId)}` : ""}{formatExecutionEnvironmentSnapshot(run.mcpServerEnvironment, run.mcpServerId) ? ` · MCP ${formatExecutionEnvironmentSnapshot(run.mcpServerEnvironment, run.mcpServerId)}` : ""}{formatExecutionEnvironmentSnapshot(run.llmProviderEnvironment, run.llmProviderId) ? ` · LLM ${formatExecutionEnvironmentSnapshot(run.llmProviderEnvironment, run.llmProviderId)}` : ""}</small></span><span className="quality-history-score">{formatMetric(run.score ?? 0)} 分</span><span>{statusLabel(run.status)}</span><small>{run.dataSource || "mock"}</small></button>)}</div> : <div className="empty-state">暂无评测任务，请先提交一次 Mock 评测。</div>}</section>
  </main>;
}
