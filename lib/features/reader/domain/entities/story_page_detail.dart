import 'package:equatable/equatable.dart';

/// Entidad pura del dominio que representa una Página individual de un Cuento (StoryPage).
///
/// El backend vincula la ilustración generada mediante `visual_config.asset_id`.
class StoryPageDetail extends Equatable {
  final String id;
  final String storyId;
  final int pageNumber;
  final String action;
  final String text;
  final Map<String, dynamic> visualConfig;
  final List<String> characterIds;
  final int? seed;
  final DateTime createdAt;
  final DateTime updatedAt;
  final String? demoIllustrationAsset;

  String? get illustrationAssetId {
    final value = visualConfig['asset_id'];
    return value is String && value.trim().isNotEmpty ? value.trim() : null;
  }

  const StoryPageDetail({
    required this.id,
    required this.storyId,
    required this.pageNumber,
    required this.action,
    required this.text,
    required this.visualConfig,
    required this.characterIds,
    this.seed,
    required this.createdAt,
    required this.updatedAt,
    this.demoIllustrationAsset,
  });

  StoryPageDetail copyWith({
    String? id,
    String? storyId,
    int? pageNumber,
    String? action,
    String? text,
    Map<String, dynamic>? visualConfig,
    List<String>? characterIds,
    int? seed,
    DateTime? createdAt,
    DateTime? updatedAt,
    String? demoIllustrationAsset,
  }) {
    return StoryPageDetail(
      id: id ?? this.id,
      storyId: storyId ?? this.storyId,
      pageNumber: pageNumber ?? this.pageNumber,
      action: action ?? this.action,
      text: text ?? this.text,
      visualConfig: visualConfig ?? this.visualConfig,
      characterIds: characterIds ?? this.characterIds,
      seed: seed ?? this.seed,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      demoIllustrationAsset:
          demoIllustrationAsset ?? this.demoIllustrationAsset,
    );
  }

  @override
  List<Object?> get props => [
    id,
    storyId,
    pageNumber,
    action,
    text,
    visualConfig,
    characterIds,
    seed,
    createdAt,
    updatedAt,
    demoIllustrationAsset,
  ];
}
