package com.twitter.finagle.http

import org.scalatest.FunSuite

class EmptyParamMapTest extends FunSuite {

  test("isValid") {
    assert(EmptyParamMap.isValid == true)
  }

  test("get") {
    assert(EmptyParamMap.get("key") == None)
  }

  test("getAll") {
    assert(EmptyParamMap.getAll("key").isEmpty == true)
  }

  test("+") {
    val map = EmptyParamMap + ("key" -> "value")
    assert(map.get("key") == Some("value"))
  }

  test("-") {
    val map = EmptyParamMap - "key"
    assert(map.get("key") == None)
  }
}
