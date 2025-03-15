import resizeHandle from 'common/chessgroundResize';
import { h, type VNode } from 'snabbdom';
import { Coords, ShowResizeHandle } from 'common/prefs';
import type PuzzleCtrl from '../ctrl';
import { storage } from 'common/storage';
import { Chessground as makeChessground } from 'chessground';

export default function (ctrl: PuzzleCtrl): VNode {
  return h('div.cg-wrap', {
    hook: {
      insert: vnode => ctrl.setChessground(makeChessground(vnode.elm as HTMLElement, makeConfig(ctrl))),
      destroy: () => ctrl.ground().destroy(),
    },
  });
}

export function makeConfig(ctrl: PuzzleCtrl): CgConfig {
  const opts = ctrl.makeCgOpts();
  return {
    fen: opts.fen,
    orientation: opts.orientation,
    turnColor: opts.turnColor,
    check: opts.check,
    lastMove: opts.lastMove,
    coordinates: ctrl.pref.coords !== Coords.Hidden,
    coordinatesOnSquares: ctrl.pref.coords === Coords.All,
    addPieceZIndex: ctrl.pref.is3d,
    addDimensionsCssVarsTo: document.body,
    movable: {
      free: false,
      color: opts.movable!.color,
      dests: opts.movable!.dests,
      showDests: ctrl.pref.destination && !ctrl.blindfold() && !ctrl.halfBlindfold(),
      rookCastle: ctrl.pref.rookCastle,
      shadowMove: ctrl.halfBlindfold(),
    },
    draggable: {
      enabled: ctrl.pref.moveEvent > 0 && !ctrl.halfBlindfold(),
      showGhost: ctrl.pref.highlight,
    },
    selectable: {
      enabled: ctrl.pref.moveEvent !== 1,
    },
    events: {
      move: ctrl.userMove,
      insert(elements) {
        resizeHandle(elements, ShowResizeHandle.Always, ctrl.node.ply);
      },
    },
    premovable: {
      enabled: opts.premovable!.enabled,
    },
    drawable: {
      enabled: true,
      defaultSnapToValidMove: storage.boolean('arrow.snap').getOrDefault(true),
    },
    highlight: {
      lastMove: ctrl.pref.highlight,
      check: ctrl.pref.highlight,
    },
    animation: {
      enabled: true,
      duration: ctrl.pref.animation.duration,
    },
    disableContextMenu: true,
  };
}
