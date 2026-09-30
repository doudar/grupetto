package com.spop.poverlay.sensor;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable mirror of the Peloton Tread {@code TreadData} wire format
 * (affernetservice {@code ITreadCallback.onSensorDataChange}).
 *
 * <p>Mirrors the style of {@link BikeData}: a hand-wired Parcelable whose field
 * read order IS the wire format. The fields are read in the exact order the
 * service writes them. The trailing reserved int MUST be read
 * or the parcel is left misaligned.
 *
 * <p>Reads only. This type never writes any control/motion value back to the tread.
 *
 * <p>The field read order is defined once, in {@link #TreadData(ParcelSource)},
 * against the {@link ParcelSource} abstraction so it can be exercised both by the
 * real {@link Parcel} on-device and by a plain-JVM unit test decoding captured
 * bytes (Android {@code Parcel} is not available in local unit tests).
 */
public class TreadData implements Parcelable {

    /**
     * Minimal read surface over an Android {@link Parcel}, so the field order can be
     * driven from a unit test without an emulator/Robolectric.
     */
    public interface ParcelSource {
        int readInt();
        long readLong();
        String readString();
        byte[] createByteArray();
        int[] createIntArray();
        int dataAvail();
    }

    public static final Creator<TreadData> CREATOR = new Creator<TreadData>() {
        @Override
        public TreadData createFromParcel(Parcel parcel) {
            return new TreadData(new AndroidParcelSource(parcel));
        }

        @Override
        public TreadData[] newArray(int i) {
            return new TreadData[i];
        }
    };

    private final long packetTime;
    private final String mcbHWVersion;
    private final String mcbFirmwareVersion;
    private final String mcbSerial;
    private final String mcbChassisSerial;
    private final int mcbState;
    private final int[] mcbError;
    private final String mcbErrorTime;
    private final int mcbCalibrationState;
    private final String mcbCalibrationData;
    private final long mcbCurrentTime;
    private final int mcbSpeedUnit;
    private final int mcbGearRatio;
    private final int mcbMaxSpeed;
    private final int mcbMaxIncline;
    private final int mcb0InclineAdc;
    private final int mcbCurrentSpeed;
    private final int mcbCurrentIncline;
    private final int mcbTargetSpeed;
    private final int mcbTargetIncline;
    private final int mcbPersonPresent;
    private final int mcbPersonPresentEnabled;
    private final long mcbTotalMiles;
    private final long mcbPassthroughModeTimeout;
    private final String scHWVersion;
    private final String scFirmwareVersion;
    private final String scSerialNumber;
    private final int scSystemState;
    private final int[] scError;
    private final long scErrorTime;
    private final int scEmergencyKeyState;
    private final int scActionButtonKeyState;
    private final int rightLedStateAndColor;
    private final int leftLedStateAndColor;
    private final int centerLedStateAndColor;
    private final int scMcbSafetyLineState;
    private final int rightButtonState;
    private final int leftButtonState;
    private final int rightEncoderData;
    private final int leftEncoderData;
    private final byte[] packetData;
    private final int mcbPersonPresentTimeout;
    private final int scInClassState;
    private final int mcbRpm;
    private final String mcbSpeedMotorSerial;
    private final String mcbInclineMotorSerial;
    private final int mcbId;
    private final int mcbMaxADC;
    private final int mcbMinADC;
    private final int mcbCurrentADC;
    private final int mcbTargetADC;
    private final int mcbPersonPresentTimer;
    private final String mcbSpeedControllerVersion;
    private final String mcbInclineControllerVersion;
    private final int actionStopState;
    private final int manualSwitchState;
    private final int countdownTimeout;
    private final int countdownTimer;
    private final int sleepTimeout;
    private final int sleepTimer;
    private final int warningCode;
    private final int eKeyDebounce;
    private final int mcbMotorType;
    private final int mcbProcessorId;
    private final int treadLocked;
    private final int keyAdcValue;
    private final int treadControlFlags;
    private final int lastInteractiveMs;
    private final int systemHealthStatus;
    private final int outPacketCount;
    private final int inPacketCount;
    private final int hardwareType;
    private final String scBootloaderVersion;
    private final int sleepLockState;
    private final int targetResistance;
    private final int reservedTrailing;

    /**
     * Reads all fields in wire order. This is the single definition of the field
     * order (used by both the on-device {@link Parcel} path and unit tests).
     */
    public TreadData(ParcelSource p) {
        this.packetTime = p.readLong();
        this.mcbHWVersion = p.readString();
        this.mcbFirmwareVersion = p.readString();
        this.mcbSerial = p.readString();
        this.mcbChassisSerial = p.readString();
        this.mcbState = p.readInt();
        this.mcbError = p.createIntArray();
        this.mcbErrorTime = p.readString();
        this.mcbCalibrationState = p.readInt();
        this.mcbCalibrationData = p.readString();
        this.mcbCurrentTime = p.readLong();
        this.mcbSpeedUnit = p.readInt();
        this.mcbGearRatio = p.readInt();
        this.mcbMaxSpeed = p.readInt();
        this.mcbMaxIncline = p.readInt();
        this.mcb0InclineAdc = p.readInt();
        this.mcbCurrentSpeed = p.readInt();
        this.mcbCurrentIncline = p.readInt();
        this.mcbTargetSpeed = p.readInt();
        this.mcbTargetIncline = p.readInt();
        this.mcbPersonPresent = p.readInt();
        this.mcbPersonPresentEnabled = p.readInt();
        this.mcbTotalMiles = p.readLong();
        this.mcbPassthroughModeTimeout = p.readLong();
        this.scHWVersion = p.readString();
        this.scFirmwareVersion = p.readString();
        this.scSerialNumber = p.readString();
        this.scSystemState = p.readInt();
        this.scError = p.createIntArray();
        this.scErrorTime = p.readLong();
        this.scEmergencyKeyState = p.readInt();
        this.scActionButtonKeyState = p.readInt();
        this.rightLedStateAndColor = p.readInt();
        this.leftLedStateAndColor = p.readInt();
        this.centerLedStateAndColor = p.readInt();
        this.scMcbSafetyLineState = p.readInt();
        this.rightButtonState = p.readInt();
        this.leftButtonState = p.readInt();
        this.rightEncoderData = p.readInt();
        this.leftEncoderData = p.readInt();
        this.packetData = p.createByteArray();
        this.mcbPersonPresentTimeout = p.readInt();
        this.scInClassState = p.readInt();
        this.mcbRpm = p.readInt();
        this.mcbSpeedMotorSerial = p.readString();
        this.mcbInclineMotorSerial = p.readString();
        this.mcbId = p.readInt();
        this.mcbMaxADC = p.readInt();
        this.mcbMinADC = p.readInt();
        this.mcbCurrentADC = p.readInt();
        this.mcbTargetADC = p.readInt();
        this.mcbPersonPresentTimer = p.readInt();
        this.mcbSpeedControllerVersion = p.readString();
        this.mcbInclineControllerVersion = p.readString();
        this.actionStopState = p.readInt();
        this.manualSwitchState = p.readInt();
        this.countdownTimeout = p.readInt();
        this.countdownTimer = p.readInt();
        this.sleepTimeout = p.readInt();
        this.sleepTimer = p.readInt();
        this.warningCode = p.readInt();
        this.eKeyDebounce = p.readInt();
        this.mcbMotorType = p.readInt();
        this.mcbProcessorId = p.readInt();
        this.treadLocked = p.readInt();
        this.keyAdcValue = p.readInt();
        this.treadControlFlags = p.readInt();
        this.lastInteractiveMs = p.readInt();
        this.systemHealthStatus = p.readInt();
        this.outPacketCount = p.readInt();
        this.inPacketCount = p.readInt();
        this.hardwareType = p.readInt();
        this.scBootloaderVersion = p.readString();
        this.sleepLockState = p.readInt();
        this.targetResistance = p.readInt();
        // Trailing reserved int. Always 0 on the wire; MUST be consumed or the
        // parcel is left misaligned (dataAvail() != 0).
        this.reservedTrailing = p.readInt();
    }

    public long getPacketTime() {
        return packetTime;
    }

    public int getMcbState() {
        return mcbState;
    }

    public int getMcbSpeedUnit() {
        return mcbSpeedUnit;
    }

    public int getMcbGearRatio() {
        return mcbGearRatio;
    }

    public int getMcbMaxSpeed() {
        return mcbMaxSpeed;
    }

    public int getMcbMaxIncline() {
        return mcbMaxIncline;
    }

    public int getMcbCurrentSpeed() {
        return mcbCurrentSpeed;
    }

    public int getMcbCurrentIncline() {
        return mcbCurrentIncline;
    }

    public int getMcbTargetSpeed() {
        return mcbTargetSpeed;
    }

    public int getMcbTargetIncline() {
        return mcbTargetIncline;
    }

    public long getMcbTotalMiles() {
        return mcbTotalMiles;
    }

    public int getScSystemState() {
        return scSystemState;
    }

    public int getMcbRpm() {
        return mcbRpm;
    }

    public int getWarningCode() {
        return warningCode;
    }

    public int getTreadLocked() {
        return treadLocked;
    }

    public boolean isTreadLocked() {
        return treadLocked != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes all fields in wire order. Present for Parcelable symmetry; grupetto
     * never sends a {@code TreadData} to the tread.
     */
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeLong(packetTime);
        parcel.writeString(mcbHWVersion);
        parcel.writeString(mcbFirmwareVersion);
        parcel.writeString(mcbSerial);
        parcel.writeString(mcbChassisSerial);
        parcel.writeInt(mcbState);
        parcel.writeIntArray(mcbError);
        parcel.writeString(mcbErrorTime);
        parcel.writeInt(mcbCalibrationState);
        parcel.writeString(mcbCalibrationData);
        parcel.writeLong(mcbCurrentTime);
        parcel.writeInt(mcbSpeedUnit);
        parcel.writeInt(mcbGearRatio);
        parcel.writeInt(mcbMaxSpeed);
        parcel.writeInt(mcbMaxIncline);
        parcel.writeInt(mcb0InclineAdc);
        parcel.writeInt(mcbCurrentSpeed);
        parcel.writeInt(mcbCurrentIncline);
        parcel.writeInt(mcbTargetSpeed);
        parcel.writeInt(mcbTargetIncline);
        parcel.writeInt(mcbPersonPresent);
        parcel.writeInt(mcbPersonPresentEnabled);
        parcel.writeLong(mcbTotalMiles);
        parcel.writeLong(mcbPassthroughModeTimeout);
        parcel.writeString(scHWVersion);
        parcel.writeString(scFirmwareVersion);
        parcel.writeString(scSerialNumber);
        parcel.writeInt(scSystemState);
        parcel.writeIntArray(scError);
        parcel.writeLong(scErrorTime);
        parcel.writeInt(scEmergencyKeyState);
        parcel.writeInt(scActionButtonKeyState);
        parcel.writeInt(rightLedStateAndColor);
        parcel.writeInt(leftLedStateAndColor);
        parcel.writeInt(centerLedStateAndColor);
        parcel.writeInt(scMcbSafetyLineState);
        parcel.writeInt(rightButtonState);
        parcel.writeInt(leftButtonState);
        parcel.writeInt(rightEncoderData);
        parcel.writeInt(leftEncoderData);
        parcel.writeByteArray(packetData);
        parcel.writeInt(mcbPersonPresentTimeout);
        parcel.writeInt(scInClassState);
        parcel.writeInt(mcbRpm);
        parcel.writeString(mcbSpeedMotorSerial);
        parcel.writeString(mcbInclineMotorSerial);
        parcel.writeInt(mcbId);
        parcel.writeInt(mcbMaxADC);
        parcel.writeInt(mcbMinADC);
        parcel.writeInt(mcbCurrentADC);
        parcel.writeInt(mcbTargetADC);
        parcel.writeInt(mcbPersonPresentTimer);
        parcel.writeString(mcbSpeedControllerVersion);
        parcel.writeString(mcbInclineControllerVersion);
        parcel.writeInt(actionStopState);
        parcel.writeInt(manualSwitchState);
        parcel.writeInt(countdownTimeout);
        parcel.writeInt(countdownTimer);
        parcel.writeInt(sleepTimeout);
        parcel.writeInt(sleepTimer);
        parcel.writeInt(warningCode);
        parcel.writeInt(eKeyDebounce);
        parcel.writeInt(mcbMotorType);
        parcel.writeInt(mcbProcessorId);
        parcel.writeInt(treadLocked);
        parcel.writeInt(keyAdcValue);
        parcel.writeInt(treadControlFlags);
        parcel.writeInt(lastInteractiveMs);
        parcel.writeInt(systemHealthStatus);
        parcel.writeInt(outPacketCount);
        parcel.writeInt(inPacketCount);
        parcel.writeInt(hardwareType);
        parcel.writeString(scBootloaderVersion);
        parcel.writeInt(sleepLockState);
        parcel.writeInt(targetResistance);
        parcel.writeInt(reservedTrailing);
    }

    /** {@link ParcelSource} backed by a real Android {@link Parcel} (on-device). */
    private static final class AndroidParcelSource implements ParcelSource {
        private final Parcel parcel;

        AndroidParcelSource(Parcel parcel) {
            this.parcel = parcel;
        }

        @Override
        public int readInt() {
            return parcel.readInt();
        }

        @Override
        public long readLong() {
            return parcel.readLong();
        }

        @Override
        public String readString() {
            return parcel.readString();
        }

        @Override
        public byte[] createByteArray() {
            return parcel.createByteArray();
        }

        @Override
        public int[] createIntArray() {
            return parcel.createIntArray();
        }

        @Override
        public int dataAvail() {
            return parcel.dataAvail();
        }
    }
}
