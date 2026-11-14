import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const KnockSenseApp());
}

class KnockSenseApp extends StatelessWidget {
  const KnockSenseApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'KnockSense',
        theme: ThemeData(
          useMaterial3: true,
          brightness: Brightness.dark,
          scaffoldBackgroundColor: const Color(0xFF0B1120),
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF7198FF),
            brightness: Brightness.dark,
          ),
        ),
        home: const MonitorScreen(),
      );
}

class Native {
  static const control = MethodChannel('knocksense/control');
  static const events = EventChannel('knocksense/events');
}

class MonitorScreen extends StatefulWidget {
  const MonitorScreen({super.key});
  @override
  State<MonitorScreen> createState() => _MonitorScreenState();
}

class _MonitorScreenState extends State<MonitorScreen> {
  final url = TextEditingController(text: 'http://192.168.1.100:8000');
  StreamSubscription<dynamic>? subscription;
  bool running = false;
  bool busy = false;
  bool fireMode = false;
  double sensitivity = .60;
  int knocks = 0;
  int fires = 0;
  String status = 'Мониторинг выключен';
  String lastEvent = 'Событий ещё нет';
  bool alarm = false;
  Timer? alertTimer;

  @override
  void initState() {
    super.initState();
    subscription = Native.events.receiveBroadcastStream().listen((value) {
      if (!mounted || value is! Map) return;
      final type = value['type']?.toString() ?? '';
      final message = value['message']?.toString() ?? '';
      setState(() {
        if (type == 'knock' || type == 'fire') {
          if (type == 'knock') { knocks++; } else { fires++; }
          lastEvent = '${DateTime.now().hour.toString().padLeft(2, '0')}:${DateTime.now().minute.toString().padLeft(2, '0')} — $message';
          alarm = true;
          alertTimer?.cancel();
          alertTimer = Timer(const Duration(seconds: 5), () {
            if (mounted) setState(() => alarm = false);
          });
        }
        status = message;
        if (type == 'error') status = 'Ошибка: $message';
        if (type == 'status' && message == 'Мониторинг остановлен') running = false;
      });
    });
    Native.control.invokeMethod<bool>('state').then((state) {
      if (mounted) setState(() => running = state ?? false);
    }).catchError((_) {});
  }

  Future<void> toggle() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      if (running) {
        await Native.control.invokeMethod('stop');
        if (mounted) setState(() { running = false; alarm = false; status = 'Мониторинг выключен'; });
      } else {
        if (!await Permission.microphone.request().isGranted) {
          throw Exception('Разрешите доступ к микрофону');
        }
        await Permission.notification.request();
        await Native.control.invokeMethod('start', {
          'server': url.text.trim(),
          'sensitivity': sensitivity,
          'fireMode': fireMode,
        });
        if (mounted) setState(() { running = true; status = 'Запуск фонового мониторинга...'; });
      }
    } catch (e) {
      if (mounted) setState(() => status = 'Ошибка: $e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> action(String name) async {
    try { await Native.control.invokeMethod(name); }
    catch (e) { if (mounted) setState(() => status = 'Ошибка: $e'); }
  }

  Future<void> configure() async {
    if (!running) return;
    try {
      await Native.control.invokeMethod('configure',
          {'sensitivity': sensitivity, 'fireMode': fireMode});
    } catch (e) {
      if (mounted) setState(() => status = 'Ошибка: $e');
    }
  }

  @override
  void dispose() {
    subscription?.cancel();
    alertTimer?.cancel();
    url.dispose();
    // Intentionally do not stop the Android foreground service.
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const blue = Color(0xFF7198FF);
    final accent = alarm ? const Color(0xFFFF667C) : blue;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(22),
          children: [
            const Row(children: [
              Icon(Icons.graphic_eq_rounded, color: blue, size: 34),
              SizedBox(width: 12),
              Text('KnockSense', style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold)),
            ]),
            const SizedBox(height: 5),
            const Text('Звук становится сигналом', style: TextStyle(color: Colors.white54)),
            const SizedBox(height: 25),
            TextField(
              controller: url,
              enabled: !running,
              decoration: const InputDecoration(
                labelText: 'Адрес FastAPI (Wi-Fi)',
                hintText: 'http://192.168.1.100:8000',
                prefixIcon: Icon(Icons.wifi),
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 32),
            Center(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 280),
                height: 184, width: 184,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: accent.withValues(alpha: .14),
                  border: Border.all(color: accent, width: 3),
                  boxShadow: [BoxShadow(color: accent.withValues(alpha: .19), blurRadius: 48)],
                ),
                child: Icon(alarm ? Icons.notifications_active_rounded : running ? Icons.graphic_eq_rounded : Icons.mic_off_rounded,
                    size: 82, color: accent),
              ),
            ),
            const SizedBox(height: 25),
            Text(alarm ? 'ВАЖНЫЙ ЗВУК!' : running ? 'Мониторинг активен' : 'Готов к работе',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: alarm ? accent : Colors.white)),
            const SizedBox(height: 10),
            Text(status, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white70)),
            const SizedBox(height: 27),
            FilledButton.icon(
              onPressed: busy ? null : toggle,
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(58), backgroundColor: running ? const Color(0xFF9C4156) : blue),
              icon: Icon(running ? Icons.stop : Icons.play_arrow),
              label: Text(running ? 'Остановить мониторинг' : 'Начать мониторинг'),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(onPressed: running ? () => action('test') : null,
                icon: const Icon(Icons.vibration), label: const Text('Проверить вибрацию')),
            const SizedBox(height: 18),
            Card(
              color: const Color(0xFF151F35),
              child: Padding(
                padding: const EdgeInsets.all(17),
                child: Column(children: [
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    const Text('Чувствительность'),
                    Text('${(sensitivity * 100).round()}%', style: const TextStyle(color: blue)),
                  ]),
                  Slider(value: sensitivity, min: .1, max: 1, onChanged: (v) {
                    setState(() => sensitivity = v);
                    configure();
                  }),
                  const Divider(),
                  SwitchListTile.adaptive(
                    title: const Text('Эксперимент: сигнализация'),
                    subtitle: const Text('Не заменяет пожарный извещатель', style: TextStyle(fontSize: 12)),
                    value: fireMode,
                    onChanged: (v) { setState(() => fireMode = v); configure(); },
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: running ? () => action('calibrate') : null,
                    icon: const Icon(Icons.tune),
                    label: const Text('Калибровать дверь (3 серии)'),
                  ),
                ]),
              ),
            ),
            const SizedBox(height: 12),
            Card(color: const Color(0xFF151F35), child: Padding(
              padding: const EdgeInsets.all(17), child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('События в этой сессии', style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 10),
                  Text('Стук: $knocks  •  Звуки сигнализации: $fires'),
                  const SizedBox(height: 8),
                  Text(lastEvent, style: const TextStyle(color: Colors.white60)),
                ],
              ),
            )),
          ],
        ),
      ),
    );
  }
}
