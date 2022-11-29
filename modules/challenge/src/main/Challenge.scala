package lila.challenge

import chess.format.FEN
import chess.variant.{ Chess960, FromPosition, Horde, RacingKings, Variant }
import chess.{ Color, Mode, Speed }
import org.joda.time.DateTime

import lila.common.Days
import lila.game.{ Game, GameRule, PerfPicker }
import lila.i18n.{ I18nKey, I18nKeys }
import lila.rating.PerfType
import lila.user.User

case class Challenge(
    _id: String,
    status: Challenge.Status,
    variant: Variant,
    initialFen: Option[FEN],
    timeControl: Challenge.TimeControl,
    mode: Mode,
    colorChoice: Challenge.ColorChoice,
    finalColor: chess.Color,
    challenger: Challenge.Challenger,
    destUser: Option[Challenge.Challenger.Registered],
    rematchOf: Option[GameId],
    createdAt: DateTime,
    seenAt: Option[DateTime], // None for open challenges, so they don't sweep
    expiresAt: DateTime,
    open: Option[Challenge.Open] = None,
    name: Option[String] = None,
    declineReason: Option[Challenge.DeclineReason] = None,
    rules: Set[GameRule] = Set.empty
):

  import Challenge.*

  inline def id = _id

  def challengerUser =
    challenger match
      case u: Challenger.Registered => u.some
      case _                        => none
  def challengerUserId = challengerUser.map(_.id)
  def challengerIsAnon =
    challenger match
      case _: Challenger.Anonymous => true
      case _                       => false
  def challengerIsOpen =
    challenger match
      case Challenger.Open => true
      case _               => false
  def destUserId = destUser.map(_.id)

  def userIds = List(challengerUserId, destUserId).flatten

  def daysPerTurn =
    timeControl match
      case TimeControl.Correspondence(d) => d.some
      case _                             => none
  def unlimited = timeControl == TimeControl.Unlimited

  def clock =
    timeControl match
      case c: TimeControl.Clock => c.some
      case _                    => none

  def hasClock = clock.isDefined

  def openDest = destUser.isEmpty
  def online   = status == Status.Created
  def active   = online || status == Status.Offline
  def canceled = status == Status.Canceled
  def declined = status == Status.Declined
  def accepted = status == Status.Accepted

  def setChallenger(u: Option[User], secret: Option[String]) =
    copy(
      challenger = u.map(toRegistered(variant, timeControl)) orElse
        secret.map(Challenger.Anonymous.apply) getOrElse Challenger.Open
    )
  def setDestUser(u: User) =
    copy(
      destUser = toRegistered(variant, timeControl)(u).some
    )

  def speed = speedOf(timeControl)

  def notableInitialFen: Option[FEN] =
    variant match
      case FromPosition | Horde | RacingKings | Chess960 => initialFen
      case _                                             => none

  def isOpen = open.isDefined

  lazy val perfType = perfTypeOf(variant, timeControl)

  def anyDeclineReason = declineReason | DeclineReason.default

  def declineWith(reason: DeclineReason) = copy(
    status = Status.Declined,
    declineReason = reason.some
  )

  def cancel = copy(status = Status.Canceled)

  def isBoardCompatible: Boolean = speed >= Speed.Blitz
  def isBotCompatible: Boolean   = speed >= Speed.Bullet

  def nonEmptyRules = rules.nonEmpty option rules

object Challenge:

  type ID = String

  sealed abstract class Status(val id: Int):
    val name = Status.this.toString.toLowerCase
  object Status:
    case object Created  extends Status(10)
    case object Offline  extends Status(15)
    case object Canceled extends Status(20)
    case object Declined extends Status(30)
    case object Accepted extends Status(40)
    val all  = List[Status](Created, Offline, Canceled, Declined, Accepted)
    val byId = all.map { s => s.id -> s }.toMap

  sealed abstract class DeclineReason(val trans: I18nKey):
    val key = DeclineReason.this.toString.toLowerCase

  object DeclineReason:
    case object Generic     extends DeclineReason(I18nKeys.challenge.declineGeneric)
    case object Later       extends DeclineReason(I18nKeys.challenge.declineLater)
    case object TooFast     extends DeclineReason(I18nKeys.challenge.declineTooFast)
    case object TooSlow     extends DeclineReason(I18nKeys.challenge.declineTooSlow)
    case object TimeControl extends DeclineReason(I18nKeys.challenge.declineTimeControl)
    case object Rated       extends DeclineReason(I18nKeys.challenge.declineRated)
    case object Casual      extends DeclineReason(I18nKeys.challenge.declineCasual)
    case object Standard    extends DeclineReason(I18nKeys.challenge.declineStandard)
    case object Variant     extends DeclineReason(I18nKeys.challenge.declineVariant)
    case object NoBot       extends DeclineReason(I18nKeys.challenge.declineNoBot)
    case object OnlyBot     extends DeclineReason(I18nKeys.challenge.declineOnlyBot)

    val default: DeclineReason = Generic
    val all: List[DeclineReason] =
      List(Generic, Later, TooFast, TooSlow, TimeControl, Rated, Casual, Standard, Variant, NoBot, OnlyBot)
    val byKey = all.map { r => r.key -> r }.toMap
    val allExceptBot: List[DeclineReason] =
      all.filterNot(r => r == NoBot || r == OnlyBot)
    def apply(key: String) = all.find { d => d.key == key.toLowerCase || d.trans.key == key } | Generic

  case class Rating(int: IntRating, provisional: Boolean):
    def show = s"$int${if (provisional) "?" else ""}"
  object Rating:
    def apply(p: lila.rating.Perf): Rating = Rating(p.intRating, p.provisional)

  enum Challenger:
    case Registered(id: UserId, rating: Rating)
    case Anonymous(secret: String)
    case Open

  sealed trait TimeControl:
    def realTime: Option[chess.Clock.Config] = none
  object TimeControl:
    def make(clock: Option[chess.Clock.Config], days: Option[Days]) =
      clock.map(Clock.apply).orElse(days map Correspondence.apply).getOrElse(Unlimited)
    case object Unlimited                 extends TimeControl
    case class Correspondence(days: Days) extends TimeControl
    case class Clock(config: chess.Clock.Config) extends TimeControl:
      override def realTime = config.some
      // All durations are expressed in seconds
      def limit     = config.limit
      def increment = config.increment
      def show      = config.show

  sealed abstract class ColorChoice(val trans: I18nKey)
  object ColorChoice:
    case object Random extends ColorChoice(I18nKeys.randomColor)
    case object White  extends ColorChoice(I18nKeys.white)
    case object Black  extends ColorChoice(I18nKeys.black)
    def apply(c: Color) = c.fold[ColorChoice](White, Black)

  case class Open(userIds: Option[(UserId, UserId)]):
    def userIdList                = userIds map { case (u1, u2) => List(u1, u2) }
    def canJoin(me: Option[User]) = userIdList.fold(true)(ids => me.map(_.id).exists(ids.has))
    def colorFor(me: Option[User], requestedColor: Option[Color]): Option[ColorChoice] =
      userIds.fold(requestedColor.fold[ColorChoice](ColorChoice.Random)(ColorChoice.apply).some) {
        case (u1, u2) =>
          me flatMap { m =>
            if (m is u1) ColorChoice.White.some
            else if (m is u2) ColorChoice.Black.some
            else none
          }
      }

  private def speedOf(timeControl: TimeControl) =
    timeControl match
      case TimeControl.Clock(config) => Speed(config)
      case _                         => Speed.Correspondence

  private def perfTypeOf(variant: Variant, timeControl: TimeControl): PerfType =
    PerfPicker
      .perfType(
        speedOf(timeControl),
        variant,
        timeControl match {
          case TimeControl.Correspondence(d) => d.some
          case _                             => none
        }
      )
      .orElse {
        (variant == FromPosition) option perfTypeOf(chess.variant.Standard, timeControl)
      }
      .|(PerfType.Correspondence)

  private val idSize = 8

  private def randomId = lila.common.ThreadLocalRandom nextString idSize

  def toRegistered(variant: Variant, timeControl: TimeControl)(u: User): Challenger.Registered =
    Challenger.Registered(u.id, Rating(u.perfs(perfTypeOf(variant, timeControl))))

  def randomColor = chess.Color.fromWhite(lila.common.ThreadLocalRandom.nextBoolean())

  def make(
      variant: Variant,
      initialFen: Option[FEN],
      timeControl: TimeControl,
      mode: Mode,
      color: String,
      challenger: Challenger,
      destUser: Option[User],
      rematchOf: Option[GameId],
      name: Option[String] = None,
      id: Option[GameId] = None,
      openToUserIds: Option[(UserId, UserId)] = None,
      rules: Set[GameRule] = Set.empty
  ): Challenge =
    val (colorChoice, finalColor) = color match
      case "white" => ColorChoice.White  -> chess.White
      case "black" => ColorChoice.Black  -> chess.Black
      case _       => ColorChoice.Random -> randomColor
    val finalMode = timeControl match
      case TimeControl.Clock(clock) if !lila.game.Game.allowRated(variant, clock.some) => Mode.Casual
      case _                                                                           => mode
    val isOpen = challenger == Challenge.Challenger.Open
    new Challenge(
      _id = id.map(_.value) | randomId,
      status = Status.Created,
      variant = variant,
      initialFen =
        if (variant == FromPosition) initialFen
        else if (variant == Chess960) initialFen filter { fen =>
          Chess960.positionNumber(fen).isDefined
        }
        else !variant.standardInitialPosition option variant.initialFen,
      timeControl = timeControl,
      mode = finalMode,
      colorChoice = colorChoice,
      finalColor = finalColor,
      challenger = challenger,
      destUser = destUser map toRegistered(variant, timeControl),
      rematchOf = rematchOf,
      createdAt = DateTime.now,
      seenAt = !isOpen option DateTime.now,
      expiresAt = if (isOpen) DateTime.now.plusDays(1) else inTwoWeeks,
      open = isOpen option Open(openToUserIds),
      name = name,
      rules = rules
    )
