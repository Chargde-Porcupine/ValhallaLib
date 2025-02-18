package frc.lib.swerve

import edu.wpi.first.math.VecBuilder
import edu.wpi.first.math.controller.PIDController
import edu.wpi.first.math.estimator.SwerveDrivePoseEstimator
import edu.wpi.first.math.geometry.*
import edu.wpi.first.math.kinematics.*
import edu.wpi.first.networktables.GenericEntry
import edu.wpi.first.wpilibj.Timer
import edu.wpi.first.wpilibj.shuffleboard.*
import edu.wpi.first.wpilibj.smartdashboard.Field2d
import edu.wpi.first.wpilibj2.command.*
import frc.lib.*
import frc.lib.swerve.SwerveDriveConstants.DrivetrainConsts.kinematics
import frc.robot.Constants
import org.littletonrobotics.junction.Logger
import org.photonvision.EstimatedRobotPose
import java.util.*
import kotlin.math.IEEErem
import kotlin.math.PI

class SwerveDriveBase(startingPose: Pose2d) : SubsystemBase() {
    private val shuffleboardTab = Shuffleboard.getTab("Drivetrain")
    private val gyroInputs = GyroIOInputsAutoLogged()
    private var poseEstimator: SwerveDrivePoseEstimator
    private val modules: List<SwerveModule> =
        when (Constants.mode){
            Constants.States.REAL -> {
                SwerveDriveConstants.modules.mapIndexed { _, swerveMod -> SwerveModule(SwerveModuleIOSparkMax(swerveMod),swerveMod.MODULE_NUMBER) }
            }
            Constants.States.REPLAY -> {
                SwerveDriveConstants.modules.mapIndexed { _, swerveMod -> SwerveModule(object: SwerveModuleIO {
                    override val turnPIDController: PIDController = swerveMod.PID_CONTROLLER
                } ,swerveMod.MODULE_NUMBER) }
            }
            Constants.States.SIM -> {
                SwerveDriveConstants.modules.mapIndexed { _, swerveMod -> SwerveModule(SwerveModuleIOSim(swerveMod.MODULE_NUMBER),swerveMod.MODULE_NUMBER) }
            }
        }
    private val gyro = when (Constants.mode){
        Constants.States.REAL -> {
            GyroIOKauai()
        }
        Constants.States.REPLAY -> {
            object : GyroIO {}
        }
        Constants.States.SIM -> {
            object : GyroIO {}
        }
    }

    private var joystickControlledEntry: GenericEntry = shuffleboardTab
            .add("Joystick Control", true)
            .withWidget("Toggle Button")
            .withProperties(mapOf("colorWhenTrue" to "green", "colorWhenFalse" to "maroon"))
            .entry

    private var poseXEntry = shuffleboardTab.add("Pose X", 0.0).entry
    private var poseYEntry = shuffleboardTab.add("Pose Y", 0.0).entry
    private var headingEntry = shuffleboardTab.add("Robot Heading", gyroInputs.yawPosition.degrees).withWidget(BuiltInWidgets.kGyro).entry
    private var seesAprilTag = shuffleboardTab.add("Sees April Tag?", false).withWidget(BuiltInWidgets.kBooleanBox).entry
    var field:Field2d

    private val camera: Camera
    init {

        SwerveDriveConstants.DrivetrainConsts.thetaPIDController.enableContinuousInput(
                180.0, -180.0
        )
        camera = Camera("hawkeye")
        zeroYaw()
        //https://github.com/Team364/BaseFalconSwerve/issues/8#issuecomment-1384799539
        Timer.delay(1.0)
        resetModulesToAbsolute()
        shuffleboardTab.add("Zero Heading", zeroHeadingCommand()).withWidget(BuiltInWidgets.kCommand)
        poseEstimator = SwerveDrivePoseEstimator(SwerveDriveConstants.DrivetrainConsts.kinematics, getYaw(), getModulePositions(), startingPose, VecBuilder.fill(0.1, 0.1, 0.1), VecBuilder.fill(0.5, 0.5, 0.5))
        field= Field2d()
        shuffleboardTab.add(field).withWidget(BuiltInWidgets.kField)
        field.getObject("Obstacle").pose = Pose2d(4.6,4.2,0.0.rotation2dFromDeg())
    }

    override fun periodic() {
        //estimate robot pose based on what the encoders say
        poseEstimator.update(getYaw(), getModulePositions())
        //estimate robot pose based on what the camera sees
        if(gyroInputs.yawVelocityRadPerSec < Math.PI && Constants.mode != Constants.States.SIM) {
                seesAprilTag.setBoolean(camera.captureFrame().hasTargets())
                val visionEst: Optional<EstimatedRobotPose>? = camera.getEstimatedPose()
                visionEst?.ifPresent { est ->
                    poseEstimator.addVisionMeasurement(
                        est.estimatedPose.toPose2d(),
                        est.timestampSeconds,
                        camera.getEstimationStdDevs(est.estimatedPose.toPose2d())
                    )
                }
            }
        //update module tabs on shuffleboard
        for (mod in modules) {
            mod.update()
        }
        if(gyroInputs.connected) {
            gyro.updateInputs(gyroInputs)
        } else{
            val deltas = modules.map { it.delta }.toTypedArray()
            val twist = kinematics.toTwist2d(*deltas);
            gyroInputs.yawPosition = (gyroInputs.yawPosition.plus(twist.dtheta.rotation2dFromRad()))
        }
        //update drivetrain tab on shuffleboard
        field.robotPose = getPose()
        poseXEntry.setDouble(getPose().x)
        poseYEntry.setDouble(getPose().y)
        headingEntry.setDouble(gyroInputs.yawPosition.degrees)
        Logger.recordOutput("Robot/Pose", getPose())


    }

    override fun simulationPeriodic() {
        for ((x, state) in constructModuleStatesFromChassisSpeeds(0.0, 0.0, 0.1, true).withIndex()) {
            modules[x].setPointEntry.setDouble(state.angle.degrees)
        }
    }

    private fun zeroYaw() {
        gyro.zeroYaw()
    }

    private fun resetModulesToAbsolute() {
        for (mod in modules) {
            mod.resetToAbsolute()
        }
    }

    fun setModuleStates(desiredStates: Array<SwerveModuleState>) {
        SwerveDriveKinematics.desaturateWheelSpeeds(desiredStates,
            SwerveDriveConstants.DrivetrainConsts.MAX_SPEED_METERS_PER_SECOND
        )

        for (mod in modules) {
            mod.setDesiredState(desiredStates[mod.index - 1])
        }
    }

    fun constructModuleStatesFromChassisSpeeds(xSpeedMetersPerSecond: Double, ySpeedMetersPerSecond: Double, turningSpeedDegreesPerSecond: Double, fieldOriented: Boolean): Array<SwerveModuleState> =
            kinematics.toSwerveModuleStates(
                    if (fieldOriented)
                        ChassisSpeeds.fromFieldRelativeSpeeds(xSpeedMetersPerSecond, ySpeedMetersPerSecond, turningSpeedDegreesPerSecond.rotation2dFromDeg().radians, getYaw())
                    else
                        ChassisSpeeds(xSpeedMetersPerSecond, ySpeedMetersPerSecond, turningSpeedDegreesPerSecond.rotation2dFromDeg().radians)
            )

    fun stop() {
        for (mod in modules) {
            mod.stop()
        }
    }

    //reset yaw on gyro so that wherever the gyro is pointing is the new forward(0) value
    fun zeroHeadingCommand(): Command {
        return runOnce { zeroYaw() }
    }
    
    //getters
    private fun getYaw(): Rotation2d = gyroInputs.yawPosition
    fun getPose(): Pose2d = Pose2d(poseEstimator.estimatedPosition.x, poseEstimator.estimatedPosition.y, poseEstimator.estimatedPosition.rotation)
    fun getModuleStates(): Array<SwerveModuleState> = modules.map { it.getState() }.toTypedArray()
    private fun getModulePositions(): Array<SwerveModulePosition> = modules.map { it.getPosition() }.toTypedArray()
}
