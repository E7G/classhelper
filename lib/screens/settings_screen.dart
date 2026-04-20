import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:hive/hive.dart';
import '../providers/asr_provider.dart';
import '../providers/question_provider.dart';
import '../providers/note_provider.dart';
import '../models/llm_config.dart';
import '../services/unified_asr_service.dart';
import 'model_management_screen.dart';
import 'storage_management_screen.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  ASRMode _asrMode = ASRMode.local;
  final _asrHostController = TextEditingController(text: 'localhost');
  final _asrPortController = TextEditingController(text: '10095');
  final _asrRemoteUrlController = TextEditingController();
  final _asrRemoteApiKeyController = TextEditingController();
  
  final _openaiApiKeyController = TextEditingController();
  final _openaiModelController = TextEditingController(text: 'gpt-4');
  
  final _ollamaHostController = TextEditingController(text: 'localhost');
  final _ollamaPortController = TextEditingController(text: '11434');
  final _ollamaModelController = TextEditingController(text: 'llama3');
  
  final _customApiUrlController = TextEditingController();
  final _customApiKeyController = TextEditingController();
  final _customModelController = TextEditingController();
  
  LLMProviderType _selectedProvider = LLMProviderType.local;
  bool _isTestingConnection = false;
  late Box _settingsBox;

  @override
  void initState() {
    super.initState();
    _settingsBox = Hive.box('settings');
    _loadConfig();
  }

  void _loadConfig() {
    final config = context.read<QuestionProvider>().llmConfig;
    
    setState(() {
      _selectedProvider = config.providerType;
      
      if (config.providerType == LLMProviderType.openai) {
        _openaiApiKeyController.text = config.apiKey ?? '';
        _openaiModelController.text = config.model;
      } else if (config.providerType == LLMProviderType.ollama) {
        final uri = Uri.parse(config.apiUrl);
        _ollamaHostController.text = uri.host;
        _ollamaPortController.text = uri.port.toString();
        _ollamaModelController.text = config.model;
      } else if (config.providerType == LLMProviderType.custom) {
        _customApiUrlController.text = config.apiUrl;
        _customApiKeyController.text = config.apiKey ?? '';
        _customModelController.text = config.model;
      }
    });
  }

  @override
  void dispose() {
    _asrHostController.dispose();
    _asrPortController.dispose();
    _asrRemoteUrlController.dispose();
    _asrRemoteApiKeyController.dispose();
    _openaiApiKeyController.dispose();
    _openaiModelController.dispose();
    _ollamaHostController.dispose();
    _ollamaPortController.dispose();
    _ollamaModelController.dispose();
    _customApiUrlController.dispose();
    _customApiKeyController.dispose();
    _customModelController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('设置'),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _buildSection(
            title: '模型管理',
            children: [
              ListTile(
                leading: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Icon(
                    Icons.download,
                    color: Theme.of(context).colorScheme.onPrimaryContainer,
                  ),
                ),
                title: const Text('模型下载与管理'),
                subtitle: const Text('下载ASR和LLM模型，或指定本地模型路径'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(builder: (_) => const ModelManagementScreen()),
                  );
                },
              ),
            ],
          ),
          const SizedBox(height: 16),
          _buildSection(
            title: 'ASR服务配置',
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'ASR模式',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 8),
                  SegmentedButton<ASRMode>(
                    segments: const [
                      ButtonSegment(
                        value: ASRMode.local,
                        label: Text('本地模型'),
                        icon: Icon(Icons.computer),
                      ),
                      ButtonSegment(
                        value: ASRMode.remote,
                        label: Text('远程API'),
                        icon: Icon(Icons.cloud),
                      ),
                    ],
                    selected: {_asrMode},
                    onSelectionChanged: (Set<ASRMode> selection) {
                      setState(() {
                        _asrMode = selection.first;
                      });
                    },
                  ),
                ],
              ),
              const SizedBox(height: 16),
              if (_asrMode == ASRMode.local) ...[
                _buildModelStatusCard(),
              ] else ...[
                TextField(
                  controller: _asrRemoteUrlController,
                  decoration: const InputDecoration(
                    labelText: 'API地址',
                    border: OutlineInputBorder(),
                    hintText: 'https://api.example.com/asr',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _asrRemoteApiKeyController,
                  decoration: const InputDecoration(
                    labelText: 'API Key (可选)',
                    border: OutlineInputBorder(),
                    suffixIcon: Icon(Icons.key),
                  ),
                  obscureText: true,
                ),
              ],
              const SizedBox(height: 12),
              Consumer<ASRProvider>(
                builder: (context, asr, _) {
                  return ElevatedButton.icon(
                    onPressed: asr.status.index <= 1
                        ? () => _connectASR()
                        : () => _disconnectASR(),
                    icon: Icon(
                      asr.status.index <= 1 ? Icons.link : Icons.link_off,
                    ),
                    label: Text(
                      asr.status.index <= 1 ? '连接' : '断开',
                    ),
                  );
                },
              ),
            ],
          ),
          const SizedBox(height: 24),
          _buildSection(
            title: 'LLM模型配置',
            children: [
              _buildProviderSelector(),
              const SizedBox(height: 16),
              _buildProviderConfig(),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _isTestingConnection ? null : _testConnection,
                      icon: _isTestingConnection
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.wifi_find),
                      label: const Text('测试连接'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: _saveLLMConfig,
                      icon: const Icon(Icons.save),
                      label: const Text('保存配置'),
                    ),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 24),
          _buildSection(
            title: '数据管理',
            children: [
              ListTile(
                leading: const Icon(Icons.storage),
                title: const Text('存储管理'),
                subtitle: const Text('按PDF分类管理笔记、问题、书签和笔画'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (_) => const StorageManagementScreen(),
                    ),
                  );
                },
              ),
              ListTile(
                leading: const Icon(Icons.delete_outline),
                title: const Text('清除所有问题'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _clearQuestions(),
              ),
              ListTile(
                leading: const Icon(Icons.delete_outline),
                title: const Text('清除所有笔记'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _clearNotes(),
              ),
            ],
          ),
          const SizedBox(height: 24),
          _buildSection(
            title: '关于',
            children: [
              ListTile(
                leading: const Icon(Icons.info_outline),
                title: const Text('版本'),
                subtitle: const Text('1.0.0'),
              ),
              ListTile(
                leading: const Icon(Icons.code),
                title: const Text('开源协议'),
                subtitle: const Text('MIT License'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildModelStatusCard() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          const Icon(
            Icons.check_circle,
            size: 18,
            color: Colors.green,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'ASR模型: 已内置',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSection({
    required String title,
    required List<Widget> children,
  }) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            ...children,
          ],
        ),
      ),
    );
  }

  Widget _buildProviderSelector() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '选择模型提供商',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        const SizedBox(height: 8),
        SegmentedButton<LLMProviderType>(
          segments: const [
            ButtonSegment(
              value: LLMProviderType.local,
              label: Text('本地模型'),
              icon: Icon(Icons.computer),
            ),
            ButtonSegment(
              value: LLMProviderType.ollama,
              label: Text('Ollama'),
              icon: Icon(Icons.storage),
            ),
            ButtonSegment(
              value: LLMProviderType.openai,
              label: Text('OpenAI'),
              icon: Icon(Icons.cloud),
            ),
            ButtonSegment(
              value: LLMProviderType.custom,
              label: Text('自定义'),
              icon: Icon(Icons.settings),
            ),
          ],
          selected: {_selectedProvider},
          onSelectionChanged: (Set<LLMProviderType> selection) {
            setState(() {
              _selectedProvider = selection.first;
            });
          },
        ),
      ],
    );
  }

  Widget _buildProviderConfig() {
    switch (_selectedProvider) {
      case LLMProviderType.local:
        return _buildLocalConfig();
      case LLMProviderType.ollama:
        return _buildOllamaConfig();
      case LLMProviderType.openai:
        return _buildOpenAIConfig();
      case LLMProviderType.custom:
        return _buildCustomConfig();
    }
  }

  Widget _buildLocalConfig() {
    final llmModelPath = _settingsBox.get('llm_model_path');
    
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              Icon(
                Icons.info_outline,
                size: 20,
                color: Theme.of(context).colorScheme.onPrimaryContainer,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '使用 llama.cpp 直接运行本地 GGUF 模型，无需外部服务。',
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onPrimaryContainer,
                    fontSize: 12,
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              Icon(
                llmModelPath != null ? Icons.check_circle : Icons.error_outline,
                size: 18,
                color: llmModelPath != null ? Colors.green : Theme.of(context).colorScheme.error,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  llmModelPath ?? '未配置LLM模型，请先在"模型管理"中下载或指定模型路径',
                  style: Theme.of(context).textTheme.bodySmall,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildOllamaConfig() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              Icon(
                Icons.info_outline,
                size: 20,
                color: Theme.of(context).colorScheme.onPrimaryContainer,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Ollama是本地运行的LLM，无需API Key。请确保已安装并运行Ollama服务。',
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onPrimaryContainer,
                    fontSize: 12,
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              flex: 2,
              child: TextField(
                controller: _ollamaHostController,
                decoration: const InputDecoration(
                  labelText: '主机地址',
                  border: OutlineInputBorder(),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: TextField(
                controller: _ollamaPortController,
                decoration: const InputDecoration(
                  labelText: '端口',
                  border: OutlineInputBorder(),
                ),
                keyboardType: TextInputType.number,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _ollamaModelController,
          decoration: const InputDecoration(
            labelText: '模型名称',
            border: OutlineInputBorder(),
            hintText: 'llama3, mistral, qwen2, etc.',
          ),
        ),
        const SizedBox(height: 8),
        TextButton.icon(
          onPressed: _fetchOllamaModels,
          icon: const Icon(Icons.refresh),
          label: const Text('获取可用模型'),
        ),
      ],
    );
  }

  Widget _buildOpenAIConfig() {
    return Column(
      children: [
        TextField(
          controller: _openaiApiKeyController,
          decoration: const InputDecoration(
            labelText: 'API Key',
            border: OutlineInputBorder(),
            suffixIcon: Icon(Icons.key),
          ),
          obscureText: true,
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _openaiModelController,
          decoration: const InputDecoration(
            labelText: '模型名称',
            border: OutlineInputBorder(),
            hintText: 'gpt-4, gpt-3.5-turbo, etc.',
          ),
        ),
      ],
    );
  }

  Widget _buildCustomConfig() {
    return Column(
      children: [
        TextField(
          controller: _customApiUrlController,
          decoration: const InputDecoration(
            labelText: 'API地址',
            border: OutlineInputBorder(),
            hintText: 'https://api.example.com/v1',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _customApiKeyController,
          decoration: const InputDecoration(
            labelText: 'API Key (可选)',
            border: OutlineInputBorder(),
            suffixIcon: Icon(Icons.key),
          ),
          obscureText: true,
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _customModelController,
          decoration: const InputDecoration(
            labelText: '模型名称',
            border: OutlineInputBorder(),
          ),
        ),
      ],
    );
  }

  Future<void> _connectASR() async {
    final asrProvider = context.read<ASRProvider>();
    
    try {
      if (_asrMode == ASRMode.remote) {
        asrProvider.configureRemoteASR(
          apiUrl: _asrRemoteUrlController.text,
          apiKey: _asrRemoteApiKeyController.text.isEmpty 
              ? null 
              : _asrRemoteApiKeyController.text,
        );
      }
      
      await asrProvider.connect();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('ASR服务连接成功')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('连接失败: $e')),
        );
      }
    }
  }

  Future<void> _disconnectASR() async {
    final asrProvider = context.read<ASRProvider>();
    await asrProvider.disconnect();
    
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已断开连接')),
      );
    }
  }

  Future<void> _testConnection() async {
    setState(() {
      _isTestingConnection = true;
    });

    try {
      final config = _buildCurrentConfig();
      final questionProvider = context.read<QuestionProvider>();
      questionProvider.configureLLM(config);
      
      final success = await questionProvider.testLLMConnection();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              success ? '连接成功！' : '连接失败，请检查配置',
            ),
            backgroundColor: success ? Colors.green : Colors.red,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('连接测试失败: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _isTestingConnection = false;
        });
      }
    }
  }

  void _saveLLMConfig() {
    final config = _buildCurrentConfig();
    context.read<QuestionProvider>().configureLLM(config);
    
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('LLM配置已保存')),
    );
  }

  LLMConfig _buildCurrentConfig() {
    switch (_selectedProvider) {
      case LLMProviderType.local:
        return LLMConfig.local(
          model: _ollamaModelController.text,
        );
      case LLMProviderType.ollama:
        return LLMConfig.ollama(
          host: _ollamaHostController.text,
          port: int.tryParse(_ollamaPortController.text) ?? 11434,
          model: _ollamaModelController.text,
        );
      case LLMProviderType.openai:
        return LLMConfig.openai(
          apiKey: _openaiApiKeyController.text,
          model: _openaiModelController.text,
        );
      case LLMProviderType.custom:
        return LLMConfig.custom(
          apiUrl: _customApiUrlController.text,
          apiKey: _customApiKeyController.text.isEmpty 
              ? null 
              : _customApiKeyController.text,
          model: _customModelController.text,
        );
    }
  }

  Future<void> _fetchOllamaModels() async {
    try {
      final config = LLMConfig.ollama(
        host: _ollamaHostController.text,
        port: int.tryParse(_ollamaPortController.text) ?? 11434,
      );
      
      final questionProvider = context.read<QuestionProvider>();
      questionProvider.configureLLM(config);
      
      final models = await questionProvider.getAvailableModels();
      
      if (mounted) {
        if (models.isNotEmpty) {
          showDialog(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('可用模型'),
              content: SizedBox(
                width: double.maxFinite,
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: models.length,
                  itemBuilder: (context, index) {
                    return ListTile(
                      title: Text(models[index]),
                      onTap: () {
                        setState(() {
                          _ollamaModelController.text = models[index];
                        });
                        Navigator.pop(context);
                      },
                    );
                  },
                ),
              ),
            ),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('未找到可用模型，请确保 Ollama 服务正在运行')),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('获取模型列表失败: $e')),
        );
      }
    }
  }

  Future<void> _clearQuestions() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认清除'),
        content: const Text('确定要清除所有问题吗？此操作不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('清除'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      context.read<QuestionProvider>().clearAllQuestions();
    }
  }

  Future<void> _clearNotes() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认清除'),
        content: const Text('确定要清除所有笔记吗？此操作不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('清除'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      context.read<NoteProvider>().clearAllNotes();
    }
  }
}
