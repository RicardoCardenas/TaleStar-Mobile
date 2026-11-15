import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import '../../data/services/ar_scene_controller.dart';
import '../cubit/ar_cubit.dart';
import '../cubit/ar_state.dart';
import '../widgets/ar_camera_feed_view.dart';
import '../widgets/ar_floating_controls.dart';
import '../widgets/ar_story_card_3d.dart';
import '../widgets/ar_top_bar.dart';

/// Flutter draws controls only; the native GL scene renders the anchored story.
class ArStoryViewerScreen extends StatefulWidget {
  final String storyId;
  final VoidCallback onExit;
  @visibleForTesting
  final bool? useNativeScene;
  final ArSceneController? sceneController;
  const ArStoryViewerScreen({
    super.key,
    required this.storyId,
    required this.onExit,
    this.useNativeScene,
    this.sceneController,
  });
  @override
  State<ArStoryViewerScreen> createState() => _ArStoryViewerScreenState();
}

class _ArStoryViewerScreenState extends State<ArStoryViewerScreen> {
  late final ArSceneController _scene;
  bool _preview = false;
  bool _physics = true;
  bool _rotate = false;
  bool _loading = true;
  String? _error;
  String? _loadedPage;
  bool _initialized = false;
  double _scale = 1;
  double _arScale = 1;
  double _baseScale = 1;
  double _lastTwist = 0;
  Offset _lastFocal = Offset.zero;
  bool get _native => widget.useNativeScene ?? ArCameraFeedView.usesNativeScene;

  @override
  void initState() {
    super.initState();
    _scene = widget.sceneController ?? ArSceneController();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_initialized) {
      _initialized = true;
      _preview = context.read<ArCubit>().state.placedPlaneId == 'preview';
      _rotate = _preview;
      if (_native && _preview) _send('preview', {'enabled': true});
    }
  }

  void _send(String command, [Map<String, Object> values = const {}]) {
    if (!_native) return;
    unawaited(
      _scene.command(command, values).catchError((Object e) {
        if (mounted) {
          setState(
            () => _error =
                'No se pudo actualizar el cuento. Intenta recolocarlo.',
          );
        }
      }),
    );
  }

  Future<void> _loadPage(ArState state) async {
    final key = '${state.currentPage?.id}:${state.currentPageIndex}';
    if (!_native || _loadedPage == key) return;
    _loadedPage = key;
    try {
      await _scene.setPage(
        state.currentPage,
        state.currentPageIndex + 1,
        state.pages.length,
      );
      if (mounted && _loadedPage == key) {
        setState(() {
          _loading = false;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted && _loadedPage == key) {
        setState(() {
          _loading = false;
          _error = 'No se pudo cargar esta página en 3D.';
        });
      }
    }
  }

  void _togglePreview() {
    if (_preview && context.read<ArCubit>().state.placedPlaneId == 'preview') {
      unawaited(_reposition());
      return;
    }
    setState(() {
      if (!_preview) _arScale = _scale;
      _preview = !_preview;
      _scale = _preview ? 1 : _arScale;
      _rotate = _preview;
    });
    _send('preview', {'enabled': _preview});
  }

  Future<void> _reposition() async {
    if (_native) {
      try {
        await _scene.command('clear');
      } catch (_) {
        return;
      }
    }
    if (mounted) context.read<ArCubit>().repositionStory();
  }

  void _gestureStart(ScaleStartDetails details, Size size) {
    _baseScale = _scale;
    _lastTwist = 0;
    _lastFocal = details.localFocalPoint;
    _send('begin', {
      'x': details.localFocalPoint.dx / size.width,
      'y': details.localFocalPoint.dy / size.height,
    });
  }

  void _gestureUpdate(ScaleUpdateDetails details, Size size) {
    final delta = details.localFocalPoint - _lastFocal;
    final twist = details.rotation - _lastTwist;
    _lastFocal = details.localFocalPoint;
    _lastTwist = details.rotation;
    _scale = (_baseScale * details.scale).clamp(0.5, 2.0);
    _send('transform', {
      'scale': _scale,
      'yaw': _rotate ? delta.dx * 0.012 + twist : twist,
      'tilt': _rotate ? delta.dy * 0.012 : 0.0,
      'move': !_rotate && details.pointerCount == 1,
      'x': details.localFocalPoint.dx / size.width,
      'y': details.localFocalPoint.dy / size.height,
    });
  }

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ArCubit, ArState>(
      builder: (context, state) {
        final cubit = context.read<ArCubit>();
        unawaited(_loadPage(state));
        final size = MediaQuery.sizeOf(context);
        return Scaffold(
          backgroundColor: Colors.transparent,
          body: Stack(
            children: [
              if (_native)
                Positioned.fill(
                  child: GestureDetector(
                    key: const Key('ar_world_gestures'),
                    behavior: HitTestBehavior.opaque,
                    onScaleStart: (d) => _gestureStart(d, size),
                    onScaleUpdate: (d) => _gestureUpdate(d, size),
                    onScaleEnd: (_) => _send('end'),
                    onDoubleTap: () => _send('stand'),
                    child: const SizedBox.expand(),
                  ),
                )
              else
                Positioned.fill(
                  child: Padding(
                    padding: const EdgeInsets.only(top: 100, bottom: 140),
                    child: ArStoryCard3d(
                      page: state.currentPage,
                      pageNumber: state.currentPageIndex + 1,
                      totalPages: state.pages.length,
                      scale: state.cardScale,
                      rotationY: state.cardRotationY,
                      rotationX: state.cardRotationX,
                      onScaleChanged: cubit.setCardScale,
                      onRotationChanged: (y, x) =>
                          cubit.setCardRotation(rotationY: y, rotationX: x),
                      onResetTransform: cubit.resetCardTransform,
                    ),
                  ),
                ),
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: ArTopBar(
                  exitButtonKey: const Key('ar_viewer_exit_button'),
                  onExit: widget.onExit,
                  title: state.story?.title ?? 'Cuento',
                  subtitle:
                      'Página ${state.currentPageIndex + 1} de ${state.pages.length}',
                  onOptions: () => _options(context),
                  showOptions: true,
                ),
              ),
              if (_native)
                Positioned(
                  top: MediaQuery.paddingOf(context).top + 86,
                  left: 12,
                  right: 12,
                  child: Column(
                    children: [
                      Wrap(
                        alignment: WrapAlignment.center,
                        spacing: 6,
                        runSpacing: 6,
                        children: [
                          _button(
                            'ar_move_rotate',
                            _rotate ? 'Girar' : 'Mover',
                            _rotate ? Icons.rotate_right : Icons.open_with,
                            _preview
                                ? null
                                : () => setState(() => _rotate = !_rotate),
                          ),
                          _button(
                            'ar_physics_toggle',
                            _preview
                                ? 'Sin física'
                                : (_physics ? 'Física: sí' : 'Física: no'),
                            Icons.grain,
                            _preview
                                ? null
                                : () {
                                    setState(() => _physics = !_physics);
                                    _send('physics', {'enabled': _physics});
                                  },
                          ),
                          _button(
                            'ar_stand_up',
                            'Levantar',
                            Icons.vertical_align_top,
                            () => _send('stand'),
                          ),
                          _button(
                            'ar_preview_toggle',
                            _preview ? 'Volver a AR' : 'Vista 3D',
                            _preview
                                ? Icons.view_in_ar
                                : Icons.view_in_ar_outlined,
                            _togglePreview,
                          ),
                        ],
                      ),
                      const SizedBox(height: 7),
                      _caption(
                        _error ??
                            (_loading
                                ? 'Cargando página…'
                                : !_preview && !state.isAnchorTracking
                                ? 'Buscando la mesa de nuevo. Mueve el teléfono despacio.'
                                : !_preview &&
                                      const [
                                        'MANUAL_ESTIMATE',
                                        'INSTANT_PLACEMENT',
                                        'PLANE_EXTENT',
                                        'ORIENTED_POINT',
                                      ].contains(state.placementPointType)
                                ? 'Apoyo estimado · ${_rotate ? 'Arrastra para girar e inclinar' : 'Arrastra para mover'}'
                                : _rotate
                                ? 'Arrastra para girar e inclinar · Pellizca para cambiar tamaño'
                                : 'Arrastra para mover sobre la mesa · Dos dedos para tamaño y giro'),
                      ),
                    ],
                  ),
                ),
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: ArFloatingControls(
                  currentPage: state.currentPageIndex + 1,
                  totalPages: state.pages.length,
                  hasPrevious: state.hasPreviousPage,
                  hasNext: state.hasNextPage,
                  onPrevious: state.hasPreviousPage ? cubit.previousPage : null,
                  onNext: state.hasNextPage ? cubit.nextPage : null,
                  onExit: widget.onExit,
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _button(
    String key,
    String label,
    IconData icon,
    VoidCallback? onPressed,
  ) => FilledButton.tonalIcon(
    key: Key(key),
    onPressed: onPressed,
    style: FilledButton.styleFrom(
      backgroundColor: const Color(0xDD211D31),
      foregroundColor: Colors.white,
      disabledBackgroundColor: const Color(0xDD211D31),
      disabledForegroundColor: Colors.white54,
      visualDensity: VisualDensity.compact,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    ),
    icon: Icon(icon, size: 18),
    label: Text(label, style: const TextStyle(fontSize: 12)),
  );
  Widget _caption(String text) => IgnorePointer(
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: .7),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Text(
        text,
        textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white, fontSize: 12),
      ),
    ),
  );
  void _options(BuildContext context) => showModalBottomSheet<void>(
    context: context,
    backgroundColor: const Color(0xFF181724),
    builder: (ctx) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            key: const Key('ar_options_reposition_tile'),
            leading: const Icon(Icons.place, color: Colors.white),
            title: const Text(
              'Recolocar cuento en otra superficie',
              style: TextStyle(color: Colors.white),
            ),
            onTap: () {
              Navigator.pop(ctx);
              unawaited(_reposition());
            },
          ),
          ListTile(
            key: const Key('ar_options_reset_tile'),
            leading: const Icon(Icons.vertical_align_top, color: Colors.white),
            title: const Text(
              'Volver a poner de pie',
              style: TextStyle(color: Colors.white),
            ),
            onTap: () {
              Navigator.pop(ctx);
              _send('stand');
              if (!_native) context.read<ArCubit>().resetCardTransform();
            },
          ),
        ],
      ),
    ),
  );
}
