package com.fourthward.game.core

import org.junit.Assert.*
import org.junit.Test

class VerticalSweepGeometryTest {
    private val plate = SliceGeometry.sliceBox(
        Box4("thin-plate", Vec4(0.0, 0.0, 2.0, 0.0), Vec4(.5, .5, .005, .5)), SliceFrame())!!

    @Test fun sphereSweepCrossesThinPlateInBothDirectionsWithBothEndpointsClear() {
        val radius = .12
        val low = Vec3(0.0, .8 - radius, 0.0)
        val high = Vec3(0.0, 3.2 - radius, 0.0)
        assertFalse(SliceGeometry.intersectsCapsule(plate, low, radius, 2 * radius))
        assertFalse(SliceGeometry.intersectsCapsule(plate, high, radius, 2 * radius))
        assertTrue(SliceGeometry.intersectsSweptCapsule(plate, low, high, radius, 2 * radius))
        assertTrue(SliceGeometry.intersectsSweptCapsule(plate, high, low, radius, 2 * radius))
        val outside = Vec3(.8, 0.0, 0.0)
        assertFalse(SliceGeometry.intersectsSweptCapsule(plate, low + outside, high + outside, radius, 2 * radius))
    }

    @Test fun changingCapsuleHeightSweepsTheSpaceBetweenDifferentTopEndpoints() {
        assertTrue(SliceGeometry.intersectsSweptCapsule(plate, Vec3.ZERO, Vec3.ZERO, .22, 1.7, 3.4))
        assertTrue(SliceGeometry.intersectsSweptCapsule(plate, Vec3.ZERO, Vec3.ZERO, .22, 3.4, 1.7))
        assertFalse(SliceGeometry.intersectsSweptCapsule(plate, Vec3.ZERO, Vec3.ZERO, .22, 1.7, 1.8))
        // Both endpoint capsules are beside the plate; the growing body crosses its center.
        val left = Vec3(-1.0, 0.0, 0.0)
        val right = Vec3(1.0, 0.0, 0.0)
        assertFalse(SliceGeometry.intersectsCapsule(plate, left, .22, 1.7))
        assertFalse(SliceGeometry.intersectsCapsule(plate, right, .22, 3.4))
        assertTrue(SliceGeometry.intersectsSweptCapsule(plate, left, right, .22, 1.7, 3.4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun changingHeightValidatesTheEndEvenWhenTheStartAlreadyCollides() {
        SliceGeometry.intersectsSweptCapsule(plate, Vec3.ZERO, Vec3.ZERO, .22, 3.4, .1)
    }
}
